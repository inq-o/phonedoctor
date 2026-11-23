package io.github.inq_o.phonedoctor.server

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FeedbackTest {
    private val cert = "ab".repeat(32)
    private val symptom = """{"source":"symptom","packageName":"com.ad","versionCode":7,"signingCertSha256":"$cert","detectorVersion":"usage-v1"}"""

    @Test
    fun `로그인 없이 App Check만으로 받고 같은 앱은 횟수만 올린다`() = serverTest {
        assertEquals(HttpStatusCode.Accepted, client.api(Post, "/feedback/false-positives", token = null, key = null, body = symptom).status)
        // 로그인 토큰을 보내도 보지 않는다 (없는 사용자여도 받음)
        assertEquals(HttpStatusCode.Accepted, client.api(Post, "/feedback/false-positives", token = "garbage", key = null, body = symptom).status)
        val rows = transaction(TestDb.db) { FalsePositives.selectAll().toList() }
        assertEquals(1, rows.size)
        assertEquals(2, rows.single()[FalsePositives.reports])
        assertEquals("usage-v1", rows.single()[FalsePositives.judgeVersion])
    }

    @Test
    fun `App Check가 없거나 틀리면 401`() = serverTest {
        assertEquals(HttpStatusCode.Unauthorized, client.api(Post, "/feedback/false-positives", token = null, key = null, appCheck = null, body = symptom).status)
        assertEquals(HttpStatusCode.Unauthorized, client.api(Post, "/feedback/false-positives", token = null, key = null, appCheck = "bad", body = symptom).status)
    }

    @Test
    fun `판정 근거가 빠지면 400`() = serverTest {
        val noDetector = """{"source":"symptom","packageName":"com.ad","versionCode":7,"signingCertSha256":"$cert"}"""
        val noModel = """{"source":"install_scan","packageName":"com.ad","versionCode":7,"signingCertSha256":"$cert","reasonCodes":[]}"""
        val badCert = """{"source":"symptom","packageName":"com.ad","versionCode":7,"signingCertSha256":"XYZ","detectorVersion":"v"}"""
        val longVersion = """{"source":"symptom","packageName":"com.ad","versionCode":7,"signingCertSha256":"$cert","detectorVersion":"${"x".repeat(65)}"}"""
        val unknownCode = """{"source":"install_scan","packageName":"com.ad","versionCode":7,"signingCertSha256":"$cert","modelVersion":"m1","score":0.9,"reasonCodes":["MADE_UP"]}"""
        val tooMany = """{"source":"install_scan","packageName":"com.ad","versionCode":7,"signingCertSha256":"$cert","modelVersion":"m1","score":0.9,"reasonCodes":["SIDELOADED","AD_SDK_HEAVY","BOOT_AUTOSTART","DEVICE_ADMIN"]}"""
        val badScore = """{"source":"install_scan","packageName":"com.ad","versionCode":7,"signingCertSha256":"$cert","modelVersion":"m1","score":3,"reasonCodes":[]}"""
        val symptomWithCodes = """{"source":"symptom","packageName":"com.ad","versionCode":7,"signingCertSha256":"$cert","detectorVersion":"v","reasonCodes":["SIDELOADED"]}"""
        val badPackage = """{"source":"symptom","packageName":"no dots here","versionCode":7,"signingCertSha256":"$cert","detectorVersion":"v"}"""
        for (body in listOf(noDetector, noModel, badCert, longVersion, unknownCode, tooMany, badScore, symptomWithCodes, badPackage)) {
            assertEquals(HttpStatusCode.BadRequest, client.api(Post, "/feedback/false-positives", token = null, key = null, body = body).status)
        }
        val ok = """{"source":"install_scan","packageName":"com.ad","versionCode":7,"signingCertSha256":"$cert","modelVersion":"m1","score":0.9,"reasonCodes":["SIDELOADED","AD_SDK_HEAVY","AD_SDK_HEAVY"]}"""
        assertEquals(HttpStatusCode.Accepted, client.api(Post, "/feedback/false-positives", token = null, key = null, body = ok).status)
        val row = transaction(TestDb.db) { FalsePositives.selectAll().single() }
        assertEquals("AD_SDK_HEAVY,SIDELOADED", row[FalsePositives.reasonCodes])
    }

    @Test
    fun `경로를 비틀면 로그인 검사를 건너뛰지 않는다`() = serverTest {
        val doubled = client.post("/v1//feedback/false-positives") {
            header("X-Firebase-AppCheck", "ok")
            contentType(ContentType.Application.Json)
            setBody(symptom)
        }
        assertEquals(HttpStatusCode.Unauthorized, doubled.status)
        assertEquals(HttpStatusCode.MethodNotAllowed, client.api(Get, "/feedback/false-positives", token = null).status)
    }

    @Test
    fun `큰 본문과 잦은 신고는 막는다`() = serverTest {
        val big = """{"source":"symptom","packageName":"com.ad","versionCode":7,"signingCertSha256":"$cert","detectorVersion":"v","pad":"${"x".repeat(5000)}"}"""
        assertEquals(HttpStatusCode.PayloadTooLarge, client.api(Post, "/feedback/false-positives", token = null, key = null, body = big).status)
        val from: io.ktor.client.request.HttpRequestBuilder.() -> Unit = { header("X-Forwarded-For", "203.0.113.9") }
        repeat(30) {
            assertEquals(HttpStatusCode.Accepted, client.api(Post, "/feedback/false-positives", token = null, key = null, body = symptom, extra = from).status)
        }
        assertEquals(HttpStatusCode.TooManyRequests, client.api(Post, "/feedback/false-positives", token = null, key = null, body = symptom, extra = from).status)
    }

    @Test
    fun `길이를 밝히지 않은 큰 본문도 막는다`() = serverTest {
        val big = """{"source":"symptom","packageName":"com.ad","versionCode":7,"signingCertSha256":"$cert","detectorVersion":"v","pad":"${"x".repeat(5000)}"}"""
        fun chunked(text: String) = object : OutgoingContent.WriteChannelContent() {
            override val contentType = ContentType.Application.Json
            override suspend fun writeTo(channel: ByteWriteChannel) = channel.writeFully(text.encodeToByteArray())
        }
        val r = client.post("/v1/feedback/false-positives") {
            header("X-Firebase-AppCheck", "ok")
            setBody(chunked(big))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, r.status)
        val ok = client.post("/v1/feedback/false-positives") {
            header("X-Firebase-AppCheck", "ok")
            setBody(chunked(symptom))
        }
        assertEquals(HttpStatusCode.Accepted, ok.status)
        // JSON이 아니면 415, 깨진 JSON은 400 (receive를 쓰지 않아도 같은 오류 형식)
        val text = client.post("/v1/feedback/false-positives") {
            header("X-Firebase-AppCheck", "ok")
            contentType(ContentType.Text.Plain)
            setBody(symptom)
        }
        assertEquals(HttpStatusCode.UnsupportedMediaType, text.status)
        assertEquals(HttpStatusCode.BadRequest, client.api(Post, "/feedback/false-positives", token = null, key = null, body = "{").status)
        // 클라이언트가 Content-Type을 고치지 않게 본문 객체에 그대로 싣는다
        suspend fun typed(type: String, body: String = symptom) = client.post("/v1/feedback/false-positives") {
            header("X-Firebase-AppCheck", "ok")
            setBody(object : OutgoingContent.ByteArrayContent() {
                override val headers = headersOf(HttpHeaders.ContentType, type)
                override fun bytes() = body.encodeToByteArray()
            })
        }.status
        // 깨진 Content-Type은 400 (500 아님), …+json은 받음, UTF-8 아닌 문자 집합·모르는 문자 집합은 415
        assertEquals(HttpStatusCode.BadRequest, typed("garbage"))
        assertEquals(HttpStatusCode.Accepted, typed("application/problem+json"))
        assertEquals(HttpStatusCode.Accepted, typed("application/json; charset=utf-8"))
        assertEquals(HttpStatusCode.UnsupportedMediaType, typed("application/json; charset=utf-16"))
        assertEquals(HttpStatusCode.UnsupportedMediaType, typed("application/json; charset=nope-9"))
        // UTF-8이 아닌 바이트는 무시되는 필드에 있어도 400
        val badUtf8 = client.post("/v1/feedback/false-positives") {
            header("X-Firebase-AppCheck", "ok")
            setBody(object : OutgoingContent.ByteArrayContent() {
                override val contentType = ContentType.Application.Json
                override fun bytes() = symptom.dropLast(1).encodeToByteArray() + ",\"x\":\"".encodeToByteArray() + byteArrayOf(0xC3.toByte(), 0x28) + "\"}".encodeToByteArray()
            })
        }
        assertEquals(HttpStatusCode.BadRequest, badUtf8.status)
        // 상한 경계: 4096바이트는 받고 4097은 413
        val pad = { n: Int -> symptom.dropLast(1) + " ".repeat(n - symptom.length) + "}" }
        assertEquals(HttpStatusCode.Accepted, typed("application/json", pad(4096)))
        assertEquals(HttpStatusCode.PayloadTooLarge, client.post("/v1/feedback/false-positives") {
            header("X-Firebase-AppCheck", "ok")
            setBody(chunked(pad(4097)))
        }.status)
    }

    @Test
    fun `X-Forwarded-For 앞쪽을 바꿔도 같은 곳으로 센다`() = serverTest {
        repeat(30) { n ->
            val spoofed: io.ktor.client.request.HttpRequestBuilder.() -> Unit = { header("X-Forwarded-For", "10.0.0.$n, 203.0.113.9") }
            assertEquals(HttpStatusCode.Accepted, client.api(Post, "/feedback/false-positives", token = null, key = null, body = symptom, extra = spoofed).status)
        }
        val again: io.ktor.client.request.HttpRequestBuilder.() -> Unit = { header("X-Forwarded-For", "10.9.9.9, 203.0.113.9") }
        assertEquals(HttpStatusCode.TooManyRequests, client.api(Post, "/feedback/false-positives", token = null, key = null, body = symptom, extra = again).status)
        assertEquals("203.0.113.9", sourceOf("1.2.3.4, 203.0.113.9", "x"))
        assertEquals("x", sourceOf(null, "x"))
        assertEquals("x", sourceOf(" ", "x"))
        // 외부 부하 분산기 뒤(PROXY_HOPS=2): 끝은 부하 분산기, 그 앞이 접속 주소
        assertEquals("198.51.100.7", sourceOf("1.2.3.4, 198.51.100.7, 35.191.0.1", "x", hops = 2))
        assertEquals("x", sourceOf("35.191.0.1", "x", hops = 2))
        // IPv6는 /64로 묶는다
        assertEquals(sourceOf("2001:db8:1:2::1", "x"), sourceOf("2001:db8:1:2:ffff::9", "x"))
        assertEquals("20:01:0d:b8:00:01:00:02::/64", sourceOf("2001:db8:1:2::abcd", "x"))
        // 포트·대괄호·zone은 떼고, IPv4를 담은 IPv6는 IPv4로, IP가 아니면 접속 주소로 (이름 조회 안 함)
        assertEquals("203.0.113.8", sourceOf("203.0.113.8:40001", "x"))
        assertEquals(sourceOf("2001:db8:1:2::1", "x"), sourceOf("[2001:db8:1:2::1]:443", "x"))
        assertEquals("203.0.113.8", sourceOf("::ffff:203.0.113.8", "x"))
        assertEquals("10.0.0.1", sourceOf("not-an-ip.example.com", "10.0.0.1"))
        assertEquals("10.0.0.1", sourceOf("999.1.1.1", "10.0.0.1"))
        assertTrue(sourceOf("2001:db8:1:3::1", "x") != sourceOf("2001:db8:1:2::1", "x"))
    }

    @Test
    fun `보낸 곳 표가 가득 차면 새 곳은 받지 않고 기존 곳은 계속 받는다`() {
        clearFeedbackLimits()
        val now = java.time.Instant.parse("2026-11-24T00:00:00Z")
        repeat(10_000) { assertTrue(allow("s$it", now)) }
        assertFalse(allow("new", now))
        assertTrue(allow("s1", now))
        // 한 시간 지나면 오래된 곳을 비우고 다시 받는다
        assertTrue(allow("new", now.plusSeconds(3601)))
        clearFeedbackLimits()
    }

    @Test
    fun `다른 경로는 여전히 로그인이 필요하다`() = serverTest {
        assertEquals(HttpStatusCode.Unauthorized, client.api(Post, "/devices", token = null, body = """{"role":"protected","fcmToken":"t","appVersionCode":1,"sdkInt":31}""").status)
    }
}
