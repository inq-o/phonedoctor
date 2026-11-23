package io.github.inq_o.phonedoctor.server

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.http.contentType
import io.ktor.http.HttpStatusCode
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `다른 경로는 여전히 로그인이 필요하다`() = serverTest {
        assertEquals(HttpStatusCode.Unauthorized, client.api(Post, "/devices", token = null, body = """{"role":"protected","fcmToken":"t","appVersionCode":1,"sdkInt":31}""").status)
    }
}
