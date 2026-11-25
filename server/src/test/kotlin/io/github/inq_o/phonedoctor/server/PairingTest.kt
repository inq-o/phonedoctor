package io.github.inq_o.phonedoctor.server

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod.Companion.Delete
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Post
import io.ktor.http.HttpMethod.Companion.Put
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val GUARDIAN2 = "id:guardian2:google:박보호"

/** 푸시 받는 기기를 가리려고 fcmToken을 `fcm-<uid>`로 등록한다 */
private suspend fun ApplicationTestBuilder.device(token: String, role: String): String {
    val uid = token.split(':')[1]
    val r = client.api(Post, "/devices", token, """{"role":"$role","fcmToken":"fcm-$uid","appVersionCode":1,"sdkInt":31}""")
    assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
    return r.json()["deviceId"]!!.jsonPrimitive.content
}

private suspend fun ApplicationTestBuilder.issueCode(token: String = SENIOR): String {
    val r = client.api(Post, "/pairing-codes", token)
    assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
    return r.contract("PairingCode")["code"]!!.jsonPrimitive.content
}

private suspend fun ApplicationTestBuilder.accept(code: String, token: String = GUARDIAN, key: String = UUID.randomUUID().toString()) =
    client.api(Post, "/pairing-codes/$code/accept", token, key = key)

/** 시니어·보호자 등록 → 코드 → 수락 → 확정까지. linkId를 돌려준다 */
private suspend fun ApplicationTestBuilder.linked(guardian: String = GUARDIAN): String {
    val r = accept(issueCode(), guardian)
    assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
    val linkId = r.json()["linkId"]!!.jsonPrimitive.content
    assertEquals(HttpStatusCode.OK, client.api(Post, "/links/$linkId/confirm", SENIOR).status)
    return linkId
}

private suspend fun HttpResponse.problem(status: HttpStatusCode, slug: String) {
    assertEquals(status, this.status, bodyAsText())
    assertEquals("https://phonedoctor.example/problems/$slug", contract("Problem")["type"]!!.jsonPrimitive.content)
}

private fun JsonObject.str(name: String) = this[name]?.jsonPrimitive?.content

class PairingCodeTest {
    @Test
    fun `시니어가 발급하면 10분짜리 6자리 코드와 QR 내용`() = serverTest { clock ->
        device(SENIOR, "protected")
        val r = client.api(Post, "/pairing-codes", SENIOR)
        assertEquals(HttpStatusCode.Created, r.status)
        val body = r.contract("PairingCode")
        val code = body.str("code")!!
        assertEquals("phonedoctor://pair?c=$code", body.str("qrPayload"))
        assertEquals(clock.instant.plus(Duration.ofMinutes(10)).toString(), body.str("expiresAt"))
    }

    @Test
    fun `보호자 기기나 등록 전 기기는 발급 불가`() = serverTest {
        device(GUARDIAN, "guardian")
        client.api(Post, "/pairing-codes", GUARDIAN).problem(HttpStatusCode.Forbidden, "forbidden")
        client.api(Post, "/pairing-codes", SENIOR).problem(HttpStatusCode.Forbidden, "device-not-registered")
    }

    @Test
    fun `새로 발급하면 이전 코드는 무효`() = serverTest {
        device(SENIOR, "protected")
        device(GUARDIAN, "guardian")
        val old = issueCode()
        val new = issueCode()
        if (old != new) accept(old).problem(HttpStatusCode.NotFound, "pairing-code-not-found")
        assertEquals(HttpStatusCode.Created, accept(new).status)
    }

    @Test
    fun `발급 재전송은 같은 코드`() = serverTest {
        device(SENIOR, "protected")
        val key = UUID.randomUUID().toString()
        val a = client.api(Post, "/pairing-codes", SENIOR, key = key).json()
        val b = client.api(Post, "/pairing-codes", SENIOR, key = key)
        assertEquals("true", b.headers["Idempotent-Replayed"])
        assertEquals(a, b.json())
    }
}

class AcceptCodeTest {
    @Test
    fun `수락하면 pending 링크, 시니어에게 LINK_REQUESTED`() = serverTest {
        val seniorId = device(SENIOR, "protected")
        val guardianId = device(GUARDIAN, "guardian")
        val r = accept(issueCode())
        assertEquals(HttpStatusCode.Created, r.status)
        val link = r.contract("Link")
        assertEquals("pending", link.str("status"))
        assertEquals(seniorId, link["protectedDevice"]!!.jsonObject.str("deviceId"))
        assertEquals("", link["protectedDevice"]!!.jsonObject.str("displayName"))
        assertEquals(guardianId, link["guardianDevice"]!!.jsonObject.str("deviceId"))
        assertEquals("김보호", link["guardianDevice"]!!.jsonObject.str("displayName"))
        val push = FakePusher.sent.single()
        assertEquals("fcm-senior1", push.token)
        assertEquals(mapOf("v" to "1", "type" to "LINK_REQUESTED", "linkId" to link.str("linkId"), "guardianName" to "김보호"), push.data)
        assertTrue(push.high)
        assertEquals(Duration.ofHours(24), push.ttl)
    }

    @Test
    fun `쓴 코드·만료 코드·없는 코드·형식 오류`() = serverTest { clock ->
        device(SENIOR, "protected")
        device(GUARDIAN, "guardian")
        device(GUARDIAN2, "guardian")
        val code = issueCode()
        assertEquals(HttpStatusCode.Created, accept(code).status)
        accept(code, GUARDIAN2).problem(HttpStatusCode.Gone, "pairing-code-used")

        val code2 = issueCode()
        clock.instant = clock.instant.plus(Duration.ofMinutes(10))
        accept(code2, GUARDIAN2).problem(HttpStatusCode.Gone, "pairing-code-expired")

        val missing = if (code2 == "000000") "000001" else "000000"
        accept(missing, GUARDIAN2).problem(HttpStatusCode.NotFound, "pairing-code-not-found")
        accept("12345", GUARDIAN2).problem(HttpStatusCode.BadRequest, "bad-request")
    }

    @Test
    fun `시니어 기기는 코드 입력 불가`() = serverTest {
        device(SENIOR, "protected")
        accept(issueCode(), SENIOR).problem(HttpStatusCode.Forbidden, "forbidden")
    }

    @Test
    fun `코드 입력은 기기당 분당 5회`() = serverTest { clock ->
        device(SENIOR, "protected")
        device(GUARDIAN, "guardian")
        val code = issueCode()
        val wrong = if (code == "000000") "000001" else "000000"
        repeat(5) {
            accept(wrong).problem(HttpStatusCode.NotFound, "pairing-code-not-found")
            clock.instant = clock.instant.plusSeconds(10)
        }
        // 첫 시도로부터 50초 → 10초 더 기다려야 함. 맞는 코드여도 막힘
        val limited = accept(code)
        limited.problem(HttpStatusCode.TooManyRequests, "too-many-attempts")
        assertEquals("10", limited.headers["Retry-After"])
        // 다른 보호자는 따로 센다
        device(GUARDIAN2, "guardian")
        accept(wrong, GUARDIAN2).problem(HttpStatusCode.NotFound, "pairing-code-not-found")

        clock.instant = clock.instant.plusSeconds(10)
        assertEquals(HttpStatusCode.Created, accept(code).status)
    }

    @Test
    fun `수락 재전송은 같은 링크, 푸시는 한 번`() = serverTest {
        device(SENIOR, "protected")
        device(GUARDIAN, "guardian")
        val code = issueCode()
        val key = UUID.randomUUID().toString()
        val a = accept(code, key = key)
        val b = accept(code, key = key)
        assertEquals(HttpStatusCode.Created, b.status)
        assertEquals("true", b.headers["Idempotent-Replayed"])
        assertEquals(a.json(), b.json())
        assertEquals(listOf("LINK_REQUESTED"), FakePusher.types())
    }

    @Test
    fun `확인 대기 중인 쌍이 새 코드를 넣으면 같은 링크로 다시 알림`() = serverTest {
        device(SENIOR, "protected")
        device(GUARDIAN, "guardian")
        val first = accept(issueCode()).json().str("linkId")
        val again = accept(issueCode())
        assertEquals(HttpStatusCode.Created, again.status)
        assertEquals(first, again.json().str("linkId"))
        assertEquals(listOf("LINK_REQUESTED", "LINK_REQUESTED"), FakePusher.types())
    }

    @Test
    fun `이미 연결된 보호자가 다시 넣으면 409`() = serverTest {
        device(SENIOR, "protected")
        device(GUARDIAN, "guardian")
        linked()
        accept(issueCode()).problem(HttpStatusCode.Conflict, "already-linked")
    }

    @Test
    fun `보호자는 최대 3명`() = serverTest {
        device(SENIOR, "protected")
        val guardians = (1..4).map { "id:g$it:google:보호$it" }
        guardians.forEach { device(it, "guardian") }
        guardians.take(3).forEach { linked(it) }
        accept(issueCode(), guardians[3]).problem(HttpStatusCode.Conflict, "guardian-limit")
    }

    @Test
    fun `푸시가 실패해도 수락은 성공`() = serverTest {
        device(SENIOR, "protected")
        device(GUARDIAN, "guardian")
        val code = issueCode()
        FakePusher.failing = true
        assertEquals(HttpStatusCode.Created, accept(code).status)
    }
}

class ConfirmLinkTest {
    @Test
    fun `시니어가 확정하면 active, 보호자에게 LINK_ACTIVATED 한 번`() = serverTest { clock ->
        device(SENIOR, "protected")
        device(GUARDIAN, "guardian")
        val linkId = accept(issueCode()).json().str("linkId")!!
        clock.instant = clock.instant.plusSeconds(90)
        val r = client.api(Post, "/links/$linkId/confirm", SENIOR)
        assertEquals(HttpStatusCode.OK, r.status)
        val link = r.contract("Link")
        assertEquals("active", link.str("status"))
        assertEquals(clock.instant.toString(), link.str("activatedAt"))
        val push = FakePusher.sent.last()
        assertEquals("fcm-guardian1", push.token)
        assertEquals(mapOf("v" to "1", "type" to "LINK_ACTIVATED", "linkId" to linkId, "protectedName" to ""), push.data)

        // 응답을 못 받아 새 키로 다시 보내도 200, 푸시는 다시 안 감
        val again = client.api(Post, "/links/$linkId/confirm", SENIOR)
        assertEquals(HttpStatusCode.OK, again.status)
        assertEquals("active", again.json().str("status"))
        assertEquals(listOf("LINK_REQUESTED", "LINK_ACTIVATED"), FakePusher.types())
    }

    @Test
    fun `보호자나 다른 시니어는 확정 불가, 해제된 링크는 409, 없는 링크는 404`() = serverTest {
        device(SENIOR, "protected")
        device(GUARDIAN, "guardian")
        val linkId = accept(issueCode()).json().str("linkId")!!
        client.api(Post, "/links/$linkId/confirm", GUARDIAN).problem(HttpStatusCode.Forbidden, "forbidden")
        device("id:senior2", "protected")
        client.api(Post, "/links/$linkId/confirm", "id:senior2").problem(HttpStatusCode.Forbidden, "forbidden")
        assertEquals(HttpStatusCode.NoContent, client.api(Delete, "/links/$linkId", SENIOR).status)
        client.api(Post, "/links/$linkId/confirm", SENIOR).problem(HttpStatusCode.Conflict, "link-revoked")
        client.api(Post, "/links/nope/confirm", SENIOR).problem(HttpStatusCode.NotFound, "not-found")
    }
}

class ListLinksTest {
    @Test
    fun `양쪽 모두 보이고, 시니어 상태는 active일 때만`() = serverTest {
        device(SENIOR, "protected")
        device(GUARDIAN, "guardian")
        val linkId = accept(issueCode()).json().str("linkId")!!
        client.api(Put, "/devices/${myId(SENIOR)}/status", body = """{"checkedAt":"2026-11-21T02:00:00Z","protection":{"notifications":true,"usageAccess":false,"notificationListener":true}}""")

        val pending = items(GUARDIAN).single()
        assertEquals("pending", pending.str("status"))
        assertNull(pending["lastCheckedAt"])
        assertNull(pending["protection"])
        assertNull(pending["lastSeenAt"])

        client.api(Post, "/links/$linkId/confirm", SENIOR)
        for (token in listOf(SENIOR, GUARDIAN)) {
            val link = items(token).single()
            assertEquals(linkId, link.str("linkId"))
            assertEquals("2026-11-21T02:00:00Z", link.str("lastCheckedAt"))
            assertEquals("false", link["protection"]!!.jsonObject.str("usageAccess"))
            assertEquals("2026-11-21T03:00:00Z", link.str("lastSeenAt"))
        }
    }

    @Test
    fun `status로 거르고, 남의 링크는 안 보임`() = serverTest {
        device(SENIOR, "protected")
        device(GUARDIAN, "guardian")
        device(GUARDIAN2, "guardian")
        val revoked = linked()
        client.api(Delete, "/links/$revoked", SENIOR)
        linked()
        assertEquals(listOf("revoked", "active"), items(SENIOR).map { it.str("status") })
        assertEquals(listOf("active"), items(SENIOR, "active").map { it.str("status") })
        assertEquals(emptyList(), items(GUARDIAN2))
        client.api(Get, "/links?status=gone", SENIOR).problem(HttpStatusCode.BadRequest, "bad-request")
    }

    private suspend fun ApplicationTestBuilder.items(token: String, status: String? = null): List<JsonObject> {
        val r = client.api(Get, "/links" + (status?.let { "?status=$it" } ?: ""), token)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val body = r.json()
        body["items"]!!.jsonArray.forEach { Contract.check("Link", it.toString()) }
        return body["items"]!!.jsonArray.map { it.jsonObject }
    }

    private fun myId(token: String) = transaction(TestDb.db) {
        Devices.selectAll().where { Devices.firebaseUid eq token.split(':')[1] }.single()[Devices.deviceId]
    }
}

class RevokeLinkTest {
    @Test
    fun `시니어가 끊으면 보호자에게 LINK_REVOKED, 다시 끊어도 204`() = serverTest {
        device(SENIOR, "protected")
        device(GUARDIAN, "guardian")
        val linkId = linked()
        assertEquals(HttpStatusCode.NoContent, client.api(Delete, "/links/$linkId", SENIOR).status)
        val push = FakePusher.sent.last()
        assertEquals("fcm-guardian1", push.token)
        assertEquals(mapOf("v" to "1", "type" to "LINK_REVOKED", "linkId" to linkId, "protectedName" to ""), push.data)
        assertEquals(false, push.high)
        assertEquals(HttpStatusCode.NoContent, client.api(Delete, "/links/$linkId", SENIOR).status)
        assertEquals(1, FakePusher.types().count { it == "LINK_REVOKED" })
    }

    @Test
    fun `보호자도 끊을 수 있고 시니어에게 알림, 남은 끊기 불가`() = serverTest {
        device(SENIOR, "protected")
        device(GUARDIAN, "guardian")
        device(GUARDIAN2, "guardian")
        val linkId = linked()
        client.api(Delete, "/links/$linkId", GUARDIAN2).problem(HttpStatusCode.Forbidden, "forbidden")
        client.api(Delete, "/links/nope", GUARDIAN).problem(HttpStatusCode.NotFound, "not-found")
        assertEquals(HttpStatusCode.NoContent, client.api(Delete, "/links/$linkId", GUARDIAN).status)
        val push = FakePusher.sent.last()
        assertEquals("fcm-senior1", push.token)
        assertEquals("김보호", push.data["guardianName"])
    }

    @Test
    fun `끊은 뒤 다시 연결하면 새 링크`() = serverTest {
        device(SENIOR, "protected")
        device(GUARDIAN, "guardian")
        val old = linked()
        client.api(Delete, "/links/$old", SENIOR)
        assertNotEquals(old, linked())
    }

    @Test
    fun `기기를 지우면 연결도 지워지고 상대에게 LINK_REVOKED`() = serverTest {
        val seniorId = device(SENIOR, "protected")
        device(GUARDIAN, "guardian")
        val linkId = linked()
        assertEquals(HttpStatusCode.NoContent, client.api(Delete, "/devices/$seniorId", SENIOR).status)
        val push = FakePusher.sent.last()
        assertEquals("fcm-guardian1", push.token)
        assertEquals(linkId, push.data["linkId"])
        assertEquals(emptyList(), transaction(TestDb.db) { Links.selectAll().toList() })
    }
}

/** 같은 자원에 요청이 동시에 와도 규칙(코드 1회 사용·푸시 1번·시도 5회·보호자 3명)이 지켜지는지 */
class PairingRaceTest {
    @Test
    fun `여러 보호자가 같은 코드를 동시에 넣으면 한 명만 연결`() = serverTest {
        device(SENIOR, "protected")
        val guardians = (1..4).map { "id:g$it:google:보호$it" }
        guardians.forEach { device(it, "guardian") }
        repeat(3) {
            val code = issueCode()
            val statuses = coroutineScope { guardians.map { g -> async { accept(code, g).status } }.awaitAll() }
            assertEquals(1, statuses.count { it == HttpStatusCode.Created }, statuses.toString())
            assertEquals(3, statuses.count { it == HttpStatusCode.Gone }, statuses.toString())
        }
    }

    @Test
    fun `다른 키로 동시에 확정해도 LINK_ACTIVATED는 한 번`() = serverTest {
        device(SENIOR, "protected")
        device(GUARDIAN, "guardian")
        val linkId = accept(issueCode()).json().str("linkId")!!
        val statuses = coroutineScope { List(4) { async { client.api(Post, "/links/$linkId/confirm", SENIOR).status } }.awaitAll() }
        assertEquals(List(4) { HttpStatusCode.OK }, statuses)
        assertEquals(1, FakePusher.types().count { it == "LINK_ACTIVATED" })
    }

    @Test
    fun `동시에 확정해도 보호자는 3명까지`() = serverTest {
        device(SENIOR, "protected")
        val guardians = (1..4).map { "id:g$it:google:보호$it" }
        guardians.forEach { device(it, "guardian") }
        val links = guardians.map { g -> accept(issueCode(), g).json().str("linkId")!! }
        val statuses = coroutineScope { links.map { l -> async { client.api(Post, "/links/$l/confirm", SENIOR).status } }.awaitAll() }
        assertEquals(3, statuses.count { it == HttpStatusCode.OK }, statuses.toString())
        assertEquals(1, statuses.count { it == HttpStatusCode.Conflict }, statuses.toString())
    }

    @Test
    fun `같은 기기를 동시에 지워도 LINK_REVOKED는 한 번`() = serverTest {
        repeat(3) { round ->
            TestDb.reset()
            FakePusher.sent.clear()
            val seniorId = device(SENIOR, "protected")
            device(GUARDIAN, "guardian")
            linked()
            val statuses = coroutineScope { List(4) { async { client.api(Delete, "/devices/$seniorId", SENIOR).status } }.awaitAll() }
            assertEquals(List(4) { HttpStatusCode.NoContent }, statuses, "round $round")
            assertEquals(1, FakePusher.types().count { it == "LINK_REVOKED" }, "round $round")
        }
    }

    @Test
    fun `동시에 몰아 넣어도 분당 5회`() = serverTest {
        device(SENIOR, "protected")
        device(GUARDIAN, "guardian")
        val wrong = if (issueCode() == "000000") "000001" else "000000"
        val statuses = coroutineScope { List(8) { async { accept(wrong).status } }.awaitAll() }
        assertEquals(5, statuses.count { it == HttpStatusCode.NotFound }, statuses.toString())
        assertEquals(3, statuses.count { it == HttpStatusCode.TooManyRequests }, statuses.toString())
    }
}
