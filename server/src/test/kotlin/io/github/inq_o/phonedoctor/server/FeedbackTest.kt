package io.github.inq_o.phonedoctor.server

import io.ktor.http.HttpMethod.Companion.Post
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
        for (body in listOf(noDetector, noModel, badCert)) {
            assertEquals(HttpStatusCode.BadRequest, client.api(Post, "/feedback/false-positives", token = null, key = null, body = body).status)
        }
        val ok = """{"source":"install_scan","packageName":"com.ad","versionCode":7,"signingCertSha256":"$cert","modelVersion":"m1","score":0.9,"reasonCodes":["SIDELOADED","AD_SDK_HEAVY","AD_SDK_HEAVY"]}"""
        assertEquals(HttpStatusCode.Accepted, client.api(Post, "/feedback/false-positives", token = null, key = null, body = ok).status)
        val row = transaction(TestDb.db) { FalsePositives.selectAll().single() }
        assertEquals("AD_SDK_HEAVY,SIDELOADED", row[FalsePositives.reasonCodes])
    }

    @Test
    fun `다른 경로는 여전히 로그인이 필요하다`() = serverTest {
        assertEquals(HttpStatusCode.Unauthorized, client.api(Post, "/devices", token = null, body = """{"role":"protected","fcmToken":"t","appVersionCode":1,"sdkInt":31}""").status)
    }
}
