package io.github.inq_o.phonedoctor.server

import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.http.HttpMethod.Companion.Put
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val RISK_SCAN =
    """{"source":"install_scan","app":{"packageName":"com.example.cleaner","appLabel":"초고속 클리너","versionCode":1,"signingCertSha256":"${"b".repeat(64)}","installSource":"ADB","firstInstallTime":"2026-11-20T01:00:00Z"},"verdict":{"level":"RISK","score":0.9,"modelVersion":"m1","reasons":[]},"detectedAt":"2026-11-21T02:59:00Z"}"""

private suspend fun ApplicationTestBuilder.join(token: String, role: String): String {
    val uid = token.split(':')[1]
    val r = client.api(Post, "/devices", token, """{"role":"$role","fcmToken":"fcm-$uid","appVersionCode":1,"sdkInt":31}""")
    return r.json()["deviceId"]!!.jsonPrimitive.content
}

/** 시니어·보호자 등록과 연결 확정. 시니어 deviceId */
private suspend fun ApplicationTestBuilder.pair(): String {
    val senior = join(SENIOR, "protected")
    join(GUARDIAN, "guardian")
    val code = client.api(Post, "/pairing-codes", SENIOR).json()["code"]!!.jsonPrimitive.content
    val linkId = client.api(Post, "/pairing-codes/$code/accept", GUARDIAN).json()["linkId"]!!.jsonPrimitive.content
    client.api(Post, "/links/$linkId/confirm", SENIOR)
    FakePusher.sent.clear()
    return senior
}

private suspend fun ApplicationTestBuilder.tick(token: String? = "tick-ok"): HttpResponse =
    client.post("/internal/tick") { token?.let { header("Authorization", "Bearer $it") } }

private suspend fun ApplicationTestBuilder.ticked(): JsonObject {
    val r = tick()
    assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
    return r.json()
}

private fun JsonObject.n(name: String) = this[name]!!.jsonPrimitive.content.toInt()

private suspend fun ApplicationTestBuilder.newAlert(body: String = RISK_SCAN): String {
    val r = client.api(Post, "/alerts", SENIOR, body)
    assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
    FakePusher.sent.clear()
    return r.json()["alertId"]!!.jsonPrimitive.content
}

private suspend fun ApplicationTestBuilder.alert(id: String) = client.api(Get, "/alerts/$id", GUARDIAN)

class TickAuthTest {
    @Test
    fun `Scheduler 토큰이 없거나 틀리면 401`() = serverTest {
        for (token in listOf(null, "forged")) {
            val r = tick(token)
            assertEquals(HttpStatusCode.Unauthorized, r.status)
            assertEquals("https://phonedoctor.example/problems/scheduler-token-invalid", r.contract("Problem")["type"]!!.jsonPrimitive.content)
        }
        // 앱 토큰으로는 못 부름
        assertEquals(HttpStatusCode.Unauthorized, tick(SENIOR).status)
    }
}

class ReminderTest {
    @Test
    fun `24시간 무응답이면 보호자에게 재알림 한 번`() = serverTest { clock ->
        pair()
        val id = newAlert()
        clock.instant = clock.instant.plus(Duration.ofHours(24)).minusSeconds(1)
        assertEquals(0, ticked().n("reminded"))
        clock.instant = clock.instant.plusSeconds(1)
        assertEquals(1, ticked().n("reminded"))
        val push = FakePusher.sent.single()
        assertEquals("fcm-guardian1", push.token)
        assertEquals("ALERT_REMINDER", push.data["type"])
        assertEquals(id, push.data["alertId"])
        assertEquals(true, push.high)
        assertNull(alert(id).contract("Alert")["remindAt"])
        clock.instant = clock.instant.plus(Duration.ofHours(24))
        assertEquals(0, ticked().n("reminded"))
        // (이때는 연결 뒤 48시간이라 연락 끊김 알림도 감)
        assertEquals(1, FakePusher.types().count { it == "ALERT_REMINDER" })
    }

    @Test
    fun `응답한 요청·알림 전용 요청은 재알림 없음`() = serverTest { clock ->
        pair()
        val id = newAlert()
        client.api(Post, "/alerts/$id/decision", GUARDIAN, """{"decision":"allow"}""")
        newAlert(
            """{"source":"symptom","kind":"notice","app":{"packageName":"com.example.ok","appLabel":"괜찮은 앱","versionCode":1,"signingCertSha256":"${"c".repeat(64)}","installSource":"GOOGLE_PLAY","firstInstallTime":"2026-11-20T01:00:00Z"},"symptom":{"date":"2026-11-21","count":9,"days":1,"firstAt":"2026-11-21T01:00:00Z","lastAt":"2026-11-21T02:00:00Z","detectorVersion":"usage-v1"},"detectedAt":"2026-11-21T02:00:00Z"}""",
        )
        FakePusher.sent.clear()
        clock.instant = clock.instant.plus(Duration.ofHours(25))
        assertEquals(0, ticked().n("reminded"))
        assertEquals(emptyList(), FakePusher.types().filter { it == "ALERT_REMINDER" })
    }

    @Test
    fun `예약 작업이 겹쳐 불려도 재알림은 한 번`() = serverTest { clock ->
        pair()
        repeat(3) { v -> newAlert(RISK_SCAN.replace("\"versionCode\":1", "\"versionCode\":${v + 1}")) }
        clock.instant = clock.instant.plus(Duration.ofHours(24))
        val counts = coroutineScope { List(4) { async { ticked().n("reminded") } }.awaitAll() }
        assertEquals(3, counts.sum(), counts.toString())
        assertEquals(3, FakePusher.types().count { it == "ALERT_REMINDER" })
    }
}

class ExpiryTest {
    @Test
    fun `7일 무응답이면 expired, 그 뒤 응답 불가`() = serverTest { clock ->
        pair()
        val id = newAlert()
        clock.instant = clock.instant.plus(Duration.ofDays(7))
        assertEquals(1, ticked().n("expired"))
        val a = alert(id).contract("Alert")
        assertEquals("expired", a["status"]!!.jsonPrimitive.content)
        assertEquals(clock.instant.toString(), a["updatedAt"]!!.jsonPrimitive.content)
        val r = client.api(Post, "/alerts/$id/decision", GUARDIAN, """{"decision":"allow"}""")
        assertEquals(HttpStatusCode.Conflict, r.status)
        // 같은 앱을 다시 보내면 새 요청
        newAlert()
    }

    @Test
    fun `생성 90일 뒤 상태와 상관없이 삭제`() = serverTest { clock ->
        pair()
        val id = newAlert()
        client.api(Post, "/alerts/$id/decision", GUARDIAN, """{"decision":"recommend_uninstall"}""")
        clock.instant = clock.instant.plus(Duration.ofDays(90)).minusSeconds(1)
        assertEquals(0, ticked().n("deletedAlerts"))
        clock.instant = clock.instant.plusSeconds(1)
        assertEquals(1, ticked().n("deletedAlerts"))
        assertEquals(0L, transaction(TestDb.db) { Alerts.selectAll().count() })
    }
}

class ContactLostTest {
    private val ok = """{"notifications":true,"usageAccess":true,"notificationListener":true}"""

    @Test
    fun `검사 보고가 48시간 없으면 보호자에게 한 번, 새 보고 뒤 다시 끊기면 또 알림`() = serverTest { clock ->
        val senior = pair()
        // 연결 확정 뒤 보고가 한 번도 없으면 확정 시각부터 셈
        clock.instant = clock.instant.plus(Duration.ofHours(48)).minusSeconds(1)
        assertEquals(0, ticked().n("contactLost"))
        clock.instant = clock.instant.plusSeconds(1)
        assertEquals(1, ticked().n("contactLost"))
        val push = FakePusher.sent.single()
        assertEquals("fcm-guardian1", push.token)
        assertEquals("CONTACT_LOST", push.data["type"])
        assertEquals("부모님", push.data["protectedName"])
        clock.instant = clock.instant.plus(Duration.ofHours(12))
        assertEquals(0, ticked().n("contactLost"))

        // 보고가 오면 풀리고, 그 뒤 48시간 다시 없으면 또 알림
        client.api(Put, "/devices/$senior/status", body = """{"checkedAt":"${clock.instant}","protection":$ok}""")
        clock.instant = clock.instant.plus(Duration.ofHours(47))
        assertEquals(0, ticked().n("contactLost"))
        clock.instant = clock.instant.plus(Duration.ofHours(1))
        assertEquals(1, ticked().n("contactLost"))
        assertEquals(2, FakePusher.types().count { it == "CONTACT_LOST" })
    }
}

class ContactLostRaceTest {
    @Test
    fun `예약 작업이 겹쳐 불려도 연락 끊김 알림은 한 번`() = serverTest { clock ->
        pair()
        clock.instant = clock.instant.plus(Duration.ofHours(48))
        val counts = coroutineScope { List(4) { async { ticked().n("contactLost") } }.awaitAll() }
        assertEquals(1, counts.sum(), counts.toString())
        assertEquals(1, FakePusher.types().count { it == "CONTACT_LOST" })
    }
}

class CleanupTest {
    @Test
    fun `180일 접속 없는 기기는 삭제, 상대에게 LINK_REVOKED`() = serverTest { clock ->
        pair()
        // 시니어만 계속 접속
        clock.instant = clock.instant.plus(Duration.ofDays(179))
        client.api(Get, "/links", SENIOR)
        clock.instant = clock.instant.plus(Duration.ofDays(1)).plusSeconds(60)
        FakePusher.sent.clear()
        assertEquals(1, ticked().n("deletedDevices"))
        assertEquals(listOf("fcm-senior1" to "LINK_REVOKED"), FakePusher.sent.filter { it.data["type"] == "LINK_REVOKED" }.map { it.token to it.data["type"] })
        assertEquals(listOf("senior1"), transaction(TestDb.db) { Devices.selectAll().map { it[Devices.firebaseUid] } })
    }

    @Test
    fun `만료 1시간 지난 연결 코드 삭제`() = serverTest { clock ->
        join(SENIOR, "protected")
        client.api(Post, "/pairing-codes", SENIOR)
        clock.instant = clock.instant.plus(Duration.ofMinutes(70))
        assertEquals(0, ticked().n("deletedCodes"))
        clock.instant = clock.instant.plusSeconds(1)
        assertEquals(1, ticked().n("deletedCodes"))
    }
}

/** PR #14 추가 리뷰에서 나온 경우 */
class TickReviewFollowupTest {
    @Test
    fun `예약 작업이 늦게 불려도 만료된 요청에는 재알림 없음`() = serverTest { clock ->
        pair()
        newAlert()
        clock.instant = clock.instant.plus(Duration.ofDays(8))
        val r = ticked()
        assertEquals(1, r.n("expired"))
        assertEquals(0, r.n("reminded"))
        assertEquals(0, FakePusher.types().count { it == "ALERT_REMINDER" })
    }

    @Test
    fun `알림 뒤 늦게 도착한 옛 보고가 다음 연락 끊김 알림을 막지 않음`() = serverTest { clock ->
        val senior = pair()
        val t0 = clock.instant
        clock.instant = t0.plus(Duration.ofHours(48))
        assertEquals(1, ticked().n("contactLost"))
        // 1시간 뒤, 끊기기 전(47시간째)에 검사한 보고가 늦게 올라옴
        clock.instant = t0.plus(Duration.ofHours(49))
        client.api(Put, "/devices/$senior/status", body = """{"checkedAt":"${t0.plus(Duration.ofHours(47))}","protection":{"notifications":true,"usageAccess":true,"notificationListener":true}}""")
        clock.instant = t0.plus(Duration.ofHours(47 + 48))
        assertEquals(1, ticked().n("contactLost"))
        assertEquals(2, FakePusher.types().count { it == "CONTACT_LOST" })
    }

    @Test
    fun `새로 연결한 보호자는 시니어의 옛 보고 때문에 바로 연락 끊김을 받지 않음`() = serverTest { clock ->
        val senior = pair()
        client.api(Put, "/devices/$senior/status", body = """{"checkedAt":"${clock.instant}","protection":{"notifications":true,"usageAccess":true,"notificationListener":true}}""")
        clock.instant = clock.instant.plus(Duration.ofDays(3))
        ticked() // 기존 보호자에게는 알림
        FakePusher.sent.clear()
        join("id:guardian2:google:박보호", "guardian")
        val code = client.api(Post, "/pairing-codes", SENIOR).json()["code"]!!.jsonPrimitive.content
        val l2 = client.api(Post, "/pairing-codes/$code/accept", "id:guardian2:google:박보호").json()["linkId"]!!.jsonPrimitive.content
        client.api(Post, "/links/$l2/confirm", SENIOR)
        FakePusher.sent.clear()
        assertEquals(0, ticked().n("contactLost"))
        clock.instant = clock.instant.plus(Duration.ofHours(48))
        assertEquals(1, ticked().n("contactLost"))
        assertEquals("fcm-guardian2", FakePusher.sent.single { it.data["type"] == "CONTACT_LOST" }.token)
    }

    @Test
    fun `형식이 깨진 Scheduler 토큰은 401로 (500 아님)`() {
        val auth = GoogleOidcAuth("https://x/internal/tick", "s@x")
        for (t in listOf("garbage", "a.b", "%%%.%%%.%%%")) assertEquals(false, auth.verify(t), t)
    }
}

class RetentionCleanupTest {
    @Test
    fun `24시간 지난 저장 응답은 새 요청이 없어도 예약 작업이 지움`() = serverTest { clock ->
        join(SENIOR, "protected")
        clock.instant = clock.instant.plus(Duration.ofHours(24)).minusSeconds(1)
        assertEquals(0, ticked().n("deletedKeys"))
        clock.instant = clock.instant.plusSeconds(2)
        assertEquals(1, ticked().n("deletedKeys"))
        assertEquals(0L, transaction(TestDb.db) { IdempotencyKeys.selectAll().count() })
    }

    @Test
    fun `기기를 지우면 코드 입력 시도 기록도 지움`() = serverTest {
        join(SENIOR, "protected")
        val g = join(GUARDIAN, "guardian")
        client.api(Post, "/pairing-codes/000000/accept", GUARDIAN)
        assertEquals(1L, transaction(TestDb.db) { PairingAttempts.selectAll().count() })
        client.api(io.ktor.http.HttpMethod.Delete, "/devices/$g", GUARDIAN)
        assertEquals(0L, transaction(TestDb.db) { PairingAttempts.selectAll().count() })
    }
}
