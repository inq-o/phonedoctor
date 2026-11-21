package io.github.inq_o.phonedoctor.server

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod.Companion.Delete
import io.ktor.http.HttpMethod.Companion.Patch
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.http.HttpMethod.Companion.Put
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

private fun registration(role: String = "protected", name: String? = null) =
    """{"role":"$role","fcmToken":"fcm-$role","appVersionCode":1,"sdkInt":31,"manufacturer":"samsung","locale":"ko-KR"${name?.let { ",\"displayName\":\"$it\"" } ?: ""}}"""

private fun deviceRow(deviceId: String) = transaction(TestDb.db) {
    Devices.selectAll().where { Devices.deviceId eq deviceId }.single()
}

private suspend fun io.ktor.server.testing.ApplicationTestBuilder.register(token: String = SENIOR, role: String = "protected"): String {
    val r = client.api(Post, "/devices", token, registration(role))
    assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
    return r.contract("Device")["deviceId"]!!.jsonPrimitive.content
}

private const val STATUS_OK = """{"checkedAt":"2026-11-21T02:00:00Z","protection":{"notifications":true,"usageAccess":true,"notificationListener":false}}"""

class AuthTest {
    @Test
    fun `토큰이 없거나 틀리면 401`() = serverTest {
        for ((token, appCheck, slug) in listOf(
            Triple(null, "ok", "id-token-missing"),
            Triple(SENIOR, null, "app-check-missing"),
            Triple("garbage", "ok", "id-token-invalid"),
            Triple(SENIOR, "forged", "app-check-invalid"),
        )) {
            val r = client.api(Post, "/devices", token, registration(), appCheck = appCheck)
            assertEquals(HttpStatusCode.Unauthorized, r.status)
            assertEquals("application/problem+json", r.headers["Content-Type"]?.substringBefore(';'))
            assertEquals("https://phonedoctor.example/problems/$slug", r.contract("Problem")["type"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `요청마다 lastSeenAt을 갱신하되 1분에 한 번까지`() = serverTest { clock ->
        val id = register()
        val first = deviceRow(id)[Devices.lastSeenAt]
        assertNull(first, "등록 요청 때는 아직 기기가 없었음")
        client.api(Put, "/devices/$id/status", body = STATUS_OK)
        val seen = deviceRow(id)[Devices.lastSeenAt]!!.toInstant()
        clock.instant = clock.instant.plusSeconds(30)
        client.api(Put, "/devices/$id/status", body = STATUS_OK)
        assertEquals(seen, deviceRow(id)[Devices.lastSeenAt]!!.toInstant())
        clock.instant = clock.instant.plusSeconds(31)
        client.api(Put, "/devices/$id/status", body = STATUS_OK)
        assertEquals(clock.instant, deviceRow(id)[Devices.lastSeenAt]!!.toInstant())
    }
}

class RegisterDeviceTest {
    @Test
    fun `시니어 등록 기본 이름은 부모님`() = serverTest {
        val r = client.api(Post, "/devices", SENIOR, registration())
        assertEquals(HttpStatusCode.Created, r.status)
        val d = r.contract("Device")
        assertEquals("protected", d["role"]!!.jsonPrimitive.content)
        assertEquals("부모님", d["displayName"]!!.jsonPrimitive.content)
        assertEquals("false", d["notifyCaution"]!!.jsonPrimitive.content)
        assertEquals("2026-11-21T03:00:00Z", d["createdAt"]!!.jsonPrimitive.content)
    }

    @Test
    fun `보호자 기본 이름은 Google 계정 이름, 익명 사용자는 보호자가 될 수 없음`() = serverTest {
        val r = client.api(Post, "/devices", GUARDIAN, registration("guardian"))
        assertEquals("김보호", r.contract("Device")["displayName"]!!.jsonPrimitive.content)
        val anon = client.api(Post, "/devices", "id:anon2", registration("guardian"))
        assertEquals(HttpStatusCode.Forbidden, anon.status)
        assertEquals("https://phonedoctor.example/problems/guardian-requires-account", anon.contract("Problem")["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `같은 사용자가 다시 등록하면 같은 기기를 갱신, 역할을 바꾸면 409`() = serverTest {
        val id = register()
        val again = client.api(Post, "/devices", SENIOR, registration(name = "엄마"))
        val d = again.contract("Device")
        assertEquals(id, d["deviceId"]!!.jsonPrimitive.content)
        assertEquals("엄마", d["displayName"]!!.jsonPrimitive.content)
        val other = client.api(Post, "/devices", "id:senior1:google:x", registration("guardian"))
        assertEquals(HttpStatusCode.Conflict, other.status)
        assertEquals("https://phonedoctor.example/problems/role-mismatch", other.contract("Problem")["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `형식 오류는 400`() = serverTest {
        for (body in listOf(
            """{"role":"protected"}""",
            """{"role":"admin","fcmToken":"t","appVersionCode":1,"sdkInt":31}""",
            registration(name = "가".repeat(21)),
            """{"role":"protected","fcmToken":" ","appVersionCode":1,"sdkInt":31}""",
            "not json",
        )) {
            val r = client.api(Post, "/devices", SENIOR, body)
            assertEquals(HttpStatusCode.BadRequest, r.status, body)
            r.contract("Problem")
        }
    }
}

class IdempotencyTest {
    @Test
    fun `같은 키로 다시 보내면 첫 응답을 그대로`() = serverTest { clock ->
        val key = UUID.randomUUID().toString()
        val first = client.api(Post, "/devices", SENIOR, registration(), key = key)
        clock.instant = clock.instant.plusSeconds(60)
        val second = client.api(Post, "/devices", SENIOR, registration(name = "바뀐 이름"), key = key)
        assertEquals(HttpStatusCode.Created, second.status)
        assertEquals(first.bodyAsText(), second.bodyAsText())
        assertEquals("true", second.headers["Idempotent-Replayed"])
        assertEquals("부모님", deviceRow(first.json()["deviceId"]!!.jsonPrimitive.content)[Devices.displayName])
    }

    @Test
    fun `같은 키가 동시에 와도 기기는 하나, 두 응답이 같음`() = serverTest {
        repeat(5) { round ->
            val token = "id:race$round"
            val key = UUID.randomUUID().toString()
            val bodies = coroutineScope {
                List(4) { async { client.api(Post, "/devices", token, registration(), key = key) } }.awaitAll()
            }.map { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()); it.bodyAsText() }
            assertEquals(1, bodies.toSet().size, bodies.toString())
            assertEquals(1L, transaction(TestDb.db) { Devices.selectAll().where { Devices.firebaseUid eq "race$round" }.count() })
        }
    }

    @Test
    fun `24시간이 지난 키는 새 요청으로 처리`()= serverTest { clock ->
        val key = UUID.randomUUID().toString()
        client.api(Post, "/devices", SENIOR, registration(), key = key)
        clock.instant = clock.instant.plusSeconds(24 * 3600 + 1)
        val r = client.api(Post, "/devices", SENIOR, registration(name = "엄마"), key = key)
        assertEquals(null, r.headers["Idempotent-Replayed"])
        assertEquals("엄마", r.json()["displayName"]!!.jsonPrimitive.content)
    }

    @Test
    fun `키가 없거나 UUID가 아니면 400`() = serverTest {
        assertEquals(HttpStatusCode.BadRequest, client.api(Post, "/devices", SENIOR, registration(), key = null).status)
        assertEquals(HttpStatusCode.BadRequest, client.api(Post, "/devices", SENIOR, registration(), key = "abc").status)
    }

    @Test
    fun `실패 응답은 저장하지 않음`() = serverTest {
        val key = UUID.randomUUID().toString()
        assertEquals(HttpStatusCode.Forbidden, client.api(Post, "/devices", "id:u3", registration("guardian"), key = key).status)
        assertEquals(HttpStatusCode.Created, client.api(Post, "/devices", "id:u3", registration(), key = key).status)
    }

    @Test
    fun `같은 키는 사용자별로 따로`() = serverTest {
        val key = UUID.randomUUID().toString()
        val a = client.api(Post, "/devices", SENIOR, registration(), key = key).json()
        val b = client.api(Post, "/devices", "id:senior2", registration(), key = key).json()
        assertNotEquals(a["deviceId"], b["deviceId"])
    }
}

class UpdateDeviceTest {
    @Test
    fun `내 기기 갱신`() = serverTest {
        val id = register(GUARDIAN, "guardian")
        val r = client.api(Patch, "/devices/$id", GUARDIAN, """{"notifyCaution":true,"fcmToken":"new","displayName":" 딸 "}""")
        assertEquals(HttpStatusCode.OK, r.status)
        val d = r.contract("Device")
        assertEquals("true", d["notifyCaution"]!!.jsonPrimitive.content)
        assertEquals("딸", d["displayName"]!!.jsonPrimitive.content)
        assertEquals("new", deviceRow(id)[Devices.fcmToken])
    }

    @Test
    fun `남의 기기는 403, 없는 기기는 404, 빈 본문·시니어의 notifyCaution은 400`() = serverTest {
        val senior = register()
        val guardian = register(GUARDIAN, "guardian")
        assertEquals(HttpStatusCode.Forbidden, client.api(Patch, "/devices/$guardian", SENIOR, """{"fcmToken":"x"}""").status)
        assertEquals(HttpStatusCode.NotFound, client.api(Patch, "/devices/nope", SENIOR, """{"fcmToken":"x"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.api(Patch, "/devices/$senior", SENIOR, "{}").status)
        assertEquals(HttpStatusCode.BadRequest, client.api(Patch, "/devices/$senior", SENIOR, """{"notifyCaution":true}""").status)
    }
}

class DeleteDeviceTest {
    @Test
    fun `내 기기 삭제, 다시 보내도 204, 남의 기기는 403`() = serverTest {
        val senior = register()
        val guardian = register(GUARDIAN, "guardian")
        assertEquals(HttpStatusCode.Forbidden, client.api(Delete, "/devices/$guardian", SENIOR).status)
        assertEquals(HttpStatusCode.NoContent, client.api(Delete, "/devices/$senior", SENIOR).status)
        assertEquals(HttpStatusCode.NoContent, client.api(Delete, "/devices/$senior", SENIOR).status)
        assertEquals(0L, transaction(TestDb.db) { Devices.selectAll().where { Devices.deviceId eq senior }.count() })
        // 지운 뒤 같은 사용자가 새로 등록하면 새 기기
        assertNotEquals(senior, register())
    }
}

class DeviceStatusTest {
    @Test
    fun `상태 보고는 lastCheckedAt과 보호 상태를 갱신`() = serverTest {
        val id = register()
        assertEquals(HttpStatusCode.NoContent, client.api(Put, "/devices/$id/status", body = STATUS_OK).status)
        val row = deviceRow(id)
        assertEquals(Instant.parse("2026-11-21T02:00:00Z"), row[Devices.lastCheckedAt]!!.toInstant())
        assertEquals(ProtectionState(notifications = true, usageAccess = true, notificationListener = false), row[Devices.protection])
    }

    @Test
    fun `늦게 도착한 옛 보고는 무시, 미래 시각은 서버 시각으로`() = serverTest { clock ->
        val id = register()
        client.api(Put, "/devices/$id/status", body = STATUS_OK)
        val older = """{"checkedAt":"2026-11-21T01:00:00Z","protection":{"notifications":false,"usageAccess":false,"notificationListener":false}}"""
        client.api(Put, "/devices/$id/status", body = older)
        assertEquals(Instant.parse("2026-11-21T02:00:00Z"), deviceRow(id)[Devices.lastCheckedAt]!!.toInstant())
        assertEquals(true, deviceRow(id)[Devices.protection]!!.notifications)
        val future = """{"checkedAt":"2027-01-01T00:00:00Z","protection":{"notifications":true,"usageAccess":false,"notificationListener":false}}"""
        client.api(Put, "/devices/$id/status", body = future)
        assertEquals(clock.instant, deviceRow(id)[Devices.lastCheckedAt]!!.toInstant())
    }

    @Test
    fun `보호자 기기·남의 기기는 403, 형식 오류는 400`() = serverTest {
        val senior = register()
        val guardian = register(GUARDIAN, "guardian")
        assertEquals(HttpStatusCode.Forbidden, client.api(Put, "/devices/$guardian/status", GUARDIAN, STATUS_OK).status)
        assertEquals(HttpStatusCode.Forbidden, client.api(Put, "/devices/$senior/status", GUARDIAN, STATUS_OK).status)
        assertEquals(HttpStatusCode.BadRequest, client.api(Put, "/devices/$senior/status", SENIOR, """{"checkedAt":"어제"}""").status)
    }
}
