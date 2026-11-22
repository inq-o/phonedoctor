package io.github.inq_o.phonedoctor.server

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod.Companion.Delete
import io.ktor.http.HttpMethod.Companion.Get
import io.ktor.http.HttpMethod.Companion.Patch
import io.ktor.http.HttpMethod.Companion.Post
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

private const val G2 = "id:guardian2:google:박보호"
private const val OUTSIDER = "id:guardian9:google:남보호"
private val CERT = "a".repeat(64)

private fun app(pkg: String = "com.example.cleaner", versionCode: Int = 1) =
    """{"packageName":"$pkg","appLabel":"초고속 클리너","versionCode":$versionCode,"signingCertSha256":"$CERT","installSource":"BROWSER_OR_FILE","installerPackage":null,"firstInstallTime":"2026-11-20T01:00:00Z"}"""

private fun scan(level: String = "RISK", pkg: String = "com.example.cleaner", versionCode: Int = 1) =
    """{"source":"install_scan","app":${app(pkg, versionCode)},"verdict":{"level":"$level","score":0.91,"modelVersion":"2026.11.1-int8","reasons":[{"code":"AD_SDK_HEAVY","weight":0.6,"evidence":{"sdks":["AdMob","Pangle"]}}]},"detectedAt":"2026-11-21T02:59:00Z"}"""

private fun symptom(date: String = "2026-11-21", count: Int = 12, kind: String? = null, pkg: String = "com.example.cleaner") =
    """{"source":"symptom",${kind?.let { "\"kind\":\"$it\"," } ?: ""}"app":${app(pkg)},"symptom":{"date":"$date","count":$count,"days":1,"firstAt":"2026-11-21T01:00:00Z","lastAt":"2026-11-21T02:00:00Z","detectorVersion":"usage-v1"},"detectedAt":"2026-11-21T02:00:00Z"}"""

private suspend fun ApplicationTestBuilder.reg(token: String, role: String): String {
    val uid = token.split(':')[1]
    val r = client.api(Post, "/devices", token, """{"role":"$role","fcmToken":"fcm-$uid","appVersionCode":1,"sdkInt":31}""")
    assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
    return r.json()["deviceId"]!!.jsonPrimitive.content
}

/** 시니어와 [guardians]를 등록하고 모두 연결(active). linkId 목록 */
private suspend fun ApplicationTestBuilder.family(vararg guardians: String = arrayOf(GUARDIAN)): List<String> {
    reg(SENIOR, "protected")
    return guardians.map { g ->
        reg(g, "guardian")
        val code = client.api(Post, "/pairing-codes", SENIOR).json()["code"]!!.jsonPrimitive.content
        val linkId = client.api(Post, "/pairing-codes/$code/accept", g).json()["linkId"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.OK, client.api(Post, "/links/$linkId/confirm", SENIOR).status)
        linkId
    }.also { FakePusher.sent.clear() }
}

private suspend fun ApplicationTestBuilder.create(body: String, key: String = UUID.randomUUID().toString()) =
    client.api(Post, "/alerts", SENIOR, body, key = key)

private suspend fun ApplicationTestBuilder.created(body: String = scan()): JsonObject {
    val r = create(body)
    assertEquals(HttpStatusCode.Created, r.status, r.bodyAsText())
    return r.contract("Alert")
}

private suspend fun ApplicationTestBuilder.decide(alertId: String, token: String = GUARDIAN, decision: String = "recommend_uninstall", message: String? = null) =
    client.api(Post, "/alerts/$alertId/decision", token, """{"decision":"$decision"${message?.let { ",\"messageToSenior\":\"$it\"" } ?: ""}}""")

private suspend fun ApplicationTestBuilder.outcome(alertId: String, outcome: String, token: String = SENIOR) =
    client.api(Post, "/alerts/$alertId/outcome", token, """{"outcome":"$outcome","occurredAt":"2026-11-21T02:30:00Z"}""")

private suspend fun HttpResponse.problem(status: HttpStatusCode, slug: String): JsonObject {
    assertEquals(status, this.status, bodyAsText())
    val body = contract("Problem")
    assertEquals("https://phonedoctor.example/problems/$slug", body.s("type"))
    return body
}

private fun JsonObject.s(name: String) = this[name]?.jsonPrimitive?.content
private fun JsonObject.o(name: String) = this[name]!!.jsonObject
private fun JsonObject.ids() = this["linkIds"]!!.jsonArray.map { it.jsonPrimitive.content }
private val JsonObject.id get() = s("alertId")!!

class CreateAlertTest {
    @Test
    fun `위험 판정을 보내면 open 요청, 보호자에게 ALERT_CREATED`() = serverTest { clock ->
        val (link) = family()
        val a = created()
        assertEquals("open", a.s("status"))
        assertEquals("install_scan", a.s("source"))
        assertEquals("approval", a.s("kind"))
        assertEquals(listOf(link), a.ids())
        assertEquals("부모님", a.o("protectedDevice").s("displayName"))
        assertEquals("AdMob", a.o("verdict")["reasons"]!!.jsonArray[0].jsonObject.o("evidence")["sdks"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals(clock.instant.toString(), a.s("createdAt"))
        assertEquals(clock.instant.toString(), a.s("updatedAt"))
        assertEquals(clock.instant.plus(Duration.ofHours(24)).toString(), a.s("remindAt"))
        val push = FakePusher.sent.single()
        assertEquals("fcm-guardian1", push.token)
        assertEquals(
            mapOf("v" to "1", "type" to "ALERT_CREATED", "alertId" to a.id, "protectedName" to "부모님", "appLabel" to "초고속 클리너", "source" to "install_scan"),
            push.data,
        )
        assertTrue(push.high)
        assertEquals(Duration.ofHours(48), push.ttl)
    }

    @Test
    fun `증상 요청 푸시에는 횟수와 날짜`() = serverTest {
        family()
        created(symptom())
        val data = FakePusher.sent.single().data
        assertEquals("symptom", data["source"])
        assertEquals("12", data["symptomCount"])
        assertEquals("2026-11-21", data["symptomDate"])
    }

    @Test
    fun `허용한 앱의 알림 전용 요청은 ALERT_NOTICE, 재알림 없음`() = serverTest {
        family()
        val a = created(symptom(kind = "notice"))
        assertEquals("notice", a.s("kind"))
        assertNull(a["remindAt"])
        val push = FakePusher.sent.single()
        assertEquals("ALERT_NOTICE", push.data["type"])
        assertEquals(false, push.high)
    }

    @Test
    fun `연결이 확정되지 않았으면 409, 보호자 기기는 403`() = serverTest {
        reg(SENIOR, "protected")
        reg(GUARDIAN, "guardian")
        val code = client.api(Post, "/pairing-codes", SENIOR).json()["code"]!!.jsonPrimitive.content
        client.api(Post, "/pairing-codes/$code/accept", GUARDIAN)
        create(scan()).problem(HttpStatusCode.Conflict, "no-active-link")
        client.api(Post, "/alerts", GUARDIAN, scan()).problem(HttpStatusCode.Forbidden, "forbidden")
    }

    @Test
    fun `형식 규칙 위반은 400`() = serverTest {
        family()
        val bad = listOf(
            """{"source":"install_scan","app":${app()},"detectedAt":"2026-11-21T02:59:00Z"}""",
            """{"source":"symptom","app":${app()},"detectedAt":"2026-11-21T02:59:00Z"}""",
            scan().replace("\"source\":\"install_scan\"", "\"source\":\"install_scan\",\"kind\":\"notice\""),
            scan(level = "SAFE"),
            scan().replace(CERT, "AB"),
            symptom(count = 0),
            symptom(date = "11/21"),
            scan().replace("\"score\":0.91", "\"score\":1.5"),
            scan().replace("초고속 클리너", "가".repeat(61)),
        )
        for (body in bad) create(body).problem(HttpStatusCode.BadRequest, "bad-request")
        assertEquals(0, FakePusher.sent.size)
    }

    @Test
    fun `주의 판정은 주의 단계를 켠 보호자에게만`() = serverTest {
        val (l1, l2) = family(GUARDIAN, G2)
        create(scan(level = "CAUTION")).problem(HttpStatusCode.Conflict, "no-recipient")
        val g2 = client.api(Get, "/links", G2).json()["items"]!!.jsonArray[0].jsonObject.o("guardianDevice").s("deviceId")
        assertEquals(HttpStatusCode.OK, client.api(Patch, "/devices/$g2", G2, """{"notifyCaution":true}""").status)
        val a = created(scan(level = "CAUTION"))
        assertEquals(listOf(l2), a.ids())
        assertEquals(listOf("fcm-guardian2"), FakePusher.sent.map { it.token })
        // 주의 단계를 안 켠 보호자는 이 요청을 볼 수 없음
        client.api(Get, "/alerts/${a.id}", GUARDIAN).problem(HttpStatusCode.NotFound, "not-found")

        // 같은 앱에 증상 근거가 붙으면 그때 받을 대상이 된 보호자에게 새 요청으로 알림
        FakePusher.sent.clear()
        val merged = create(symptom()).contract("Alert")
        assertEquals(listOf(l1, l2).sorted(), merged.ids())
        assertEquals(
            mapOf("fcm-guardian1" to "ALERT_CREATED", "fcm-guardian2" to "ALERT_UPDATED"),
            FakePusher.sent.associate { it.token to it.data["type"] },
        )
    }

    @Test
    fun `같은 앱의 열린 요청에는 근거만 합침, source는 처음 값, 옛 증상 값은 무시`() = serverTest { clock ->
        family()
        val first = created()
        clock.instant = clock.instant.plusSeconds(600)
        val r = create(symptom(count = 12))
        assertEquals(HttpStatusCode.OK, r.status)
        val a = r.contract("Alert")
        assertEquals(first.id, a.id)
        assertEquals("install_scan", a.s("source"))
        assertEquals("12", a.o("symptom").s("count"))
        assertEquals(clock.instant.toString(), a.s("updatedAt"))
        assertEquals(first.s("remindAt"), a.s("remindAt"))
        assertEquals(listOf("ALERT_CREATED", "ALERT_UPDATED"), FakePusher.types())

        // 재전송 순서가 뒤바뀐 옛 값 → 그대로, 푸시 없음
        clock.instant = clock.instant.plusSeconds(600)
        val stale = create(symptom(count = 5)).json()
        assertEquals("12", stale.o("symptom").s("count"))
        assertEquals(a.s("updatedAt"), stale.s("updatedAt"))
        assertEquals(2, FakePusher.sent.size)

        // 다음 날 값은 횟수가 적어도 최신
        val next = create(symptom(date = "2026-11-22", count = 3)).json()
        assertEquals("3", next.o("symptom").s("count"))
        assertEquals(listOf("ALERT_CREATED", "ALERT_UPDATED", "ALERT_UPDATED"), FakePusher.types())

        // 설치 시점 판정을 다시 보내도 verdict는 덮어쓰지 않음
        val again = create(scan(level = "CAUTION")).json()
        assertEquals("RISK", again.o("verdict").s("level"))
    }

    @Test
    fun `버전이 다르면 다른 요청`() = serverTest {
        family()
        val a = created(scan(versionCode = 1))
        val b = created(scan(versionCode = 2))
        assertNotEquals(a.id, b.id)
    }

    @Test
    fun `재전송은 같은 응답, 푸시는 한 번`() = serverTest {
        family()
        val key = UUID.randomUUID().toString()
        val a = create(scan(), key)
        val b = create(scan(), key)
        assertEquals("true", b.headers["Idempotent-Replayed"])
        assertEquals(HttpStatusCode.Created, b.status)
        assertEquals(a.bodyAsText(), b.bodyAsText())
        assertEquals(1, FakePusher.sent.size)
    }

    @Test
    fun `다른 키로 같은 앱이 동시에 와도 요청은 하나`() = serverTest {
        family()
        val bodies = coroutineScope { List(4) { async { create(scan()) } }.awaitAll() }
        assertEquals(1, bodies.count { it.status == HttpStatusCode.Created }, bodies.map { it.status }.toString())
        assertEquals(3, bodies.count { it.status == HttpStatusCode.OK })
        assertEquals(1, bodies.map { it.json().id }.toSet().size)
    }
}

class ListAlertsTest {
    @Test
    fun `시니어는 자기 것, 보호자는 받은 것, 남은 못 봄`() = serverTest {
        family()
        reg(OUTSIDER, "guardian")
        val a = created()
        assertEquals(listOf(a.id), items(SENIOR).map { it.id })
        assertEquals(listOf(a.id), items(GUARDIAN).map { it.id })
        assertEquals(emptyList(), items(OUTSIDER))
        assertEquals(a, client.api(Get, "/alerts/${a.id}", GUARDIAN).contract("Alert"))
        client.api(Get, "/alerts/${a.id}", OUTSIDER).problem(HttpStatusCode.NotFound, "not-found")
        client.api(Get, "/alerts/nope", SENIOR).problem(HttpStatusCode.NotFound, "not-found")
        client.api(Get, "/alerts", "id:nobody").problem(HttpStatusCode.Forbidden, "device-not-registered")
    }

    @Test
    fun `status·updatedSince로 거르고 쪽 나눔`() = serverTest { clock ->
        family()
        val ids = (1..3).map { v ->
            clock.instant = clock.instant.plusSeconds(60)
            created(scan(versionCode = v)).id
        }
        clock.instant = clock.instant.plusSeconds(60)
        decide(ids[0])
        // 둘째 요청을 만든 시각 이후에 바뀐 것: 셋째 요청, 결정된 첫 요청
        val t1 = clock.instant.minusSeconds(120)
        assertEquals(listOf(ids[1], ids[2]), items(GUARDIAN, "status=open").map { it.id })
        // 첫 요청은 결정되며 updatedAt이 바뀌어 맨 뒤로
        assertEquals(listOf(ids[2], ids[0]), items(GUARDIAN, "updatedSince=$t1").map { it.id })

        val p1 = client.api(Get, "/alerts?pageSize=2", GUARDIAN).json()
        val token = p1.s("nextPageToken")!!
        val p2 = client.api(Get, "/alerts?pageSize=2&pageToken=$token", GUARDIAN).json()
        assertNull(p2["nextPageToken"])
        val all = (p1["items"]!!.jsonArray + p2["items"]!!.jsonArray).map { it.jsonObject.id }
        assertEquals(listOf(ids[1], ids[2], ids[0]), all)

        for (bad in listOf("status=gone", "pageSize=0", "pageSize=51", "pageToken=bm9wZQ", "updatedSince=yesterday")) {
            client.api(Get, "/alerts?$bad", GUARDIAN).problem(HttpStatusCode.BadRequest, "bad-request")
        }
    }

    private suspend fun ApplicationTestBuilder.items(token: String, query: String = ""): List<JsonObject> {
        val r = client.api(Get, "/alerts?$query", token)
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        return r.json()["items"]!!.jsonArray.map { it.jsonObject.also { a -> Contract.check("Alert", a.toString()) } }
    }
}

class DecisionTest {
    @Test
    fun `먼저 온 응답만 유효, 시니어에게 DECISION_MADE, 다른 보호자에게 ALERT_UPDATED`() = serverTest {
        family(GUARDIAN, G2)
        val a = created()
        FakePusher.sent.clear()
        val r = decide(a.id, message = "엄마 이거 광고 앱이에요")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val body = r.contract("Alert")
        assertEquals("decided", body.s("status"))
        assertEquals("recommend_uninstall", body.o("decision").s("decision"))
        assertEquals("엄마 이거 광고 앱이에요", body.o("decision").s("messageToSenior"))
        assertEquals("김보호", body.o("decision").o("decidedBy").s("displayName"))
        assertNull(body["remindAt"])
        assertEquals(
            mapOf(
                "fcm-senior1" to mapOf("v" to "1", "type" to "DECISION_MADE", "alertId" to a.id, "decision" to "recommend_uninstall", "guardianName" to "김보호", "packageName" to "com.example.cleaner"),
                "fcm-guardian2" to mapOf("v" to "1", "type" to "ALERT_UPDATED", "alertId" to a.id),
            ),
            FakePusher.sent.associate { it.token to it.data },
        )

        val late = decide(a.id, G2, "allow").problem(HttpStatusCode.Conflict, "already-decided")
        val existing = late["existingDecision"]!!.jsonObject
        Contract.check("Decision", existing.toString())
        assertEquals("김보호", existing.o("decidedBy").s("displayName"))

        // 같은 보호자가 같은 응답을 새 키로 다시 보내면 200, 푸시 없음
        assertEquals(HttpStatusCode.OK, decide(a.id).status)
        assertEquals(2, FakePusher.sent.size)
    }

    @Test
    fun `동시에 응답해도 하나만 성공`() = serverTest {
        family(GUARDIAN, G2)
        repeat(3) { v ->
            val a = created(scan(versionCode = v + 1))
            val statuses = coroutineScope { listOf(GUARDIAN, G2).map { g -> async { decide(a.id, g).status } }.awaitAll() }
            assertEquals(setOf(HttpStatusCode.OK, HttpStatusCode.Conflict), statuses.toSet(), statuses.toString())
        }
    }

    @Test
    fun `시니어·받지 않은 보호자 403, 알림 전용에 허용 400, 긴 메시지 400`() = serverTest {
        family()
        reg(OUTSIDER, "guardian")
        val a = created()
        decide(a.id, SENIOR).problem(HttpStatusCode.Forbidden, "forbidden")
        decide(a.id, OUTSIDER).problem(HttpStatusCode.Forbidden, "forbidden")
        decide(a.id, message = "가".repeat(41)).problem(HttpStatusCode.BadRequest, "bad-request")
        decide("nope").problem(HttpStatusCode.NotFound, "not-found")
        val notice = created(symptom(kind = "notice", pkg = "com.example.allowed"))
        decide(notice.id, decision = "allow").problem(HttpStatusCode.BadRequest, "bad-request")
        assertEquals(HttpStatusCode.OK, decide(notice.id).status)
    }
}

class OutcomeTest {
    @Test
    fun `응답 뒤 결과 보고 → resolved, 보호자에게 ALERT_RESOLVED`() = serverTest {
        family()
        val a = created()
        decide(a.id)
        FakePusher.sent.clear()
        val r = outcome(a.id, "uninstalled")
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        val body = r.contract("Alert")
        assertEquals("resolved", body.s("status"))
        assertEquals("uninstalled", body.o("outcome").s("outcome"))
        assertEquals("2026-11-21T02:30:00Z", body.o("outcome").s("occurredAt"))
        assertEquals(
            mapOf("v" to "1", "type" to "ALERT_RESOLVED", "alertId" to a.id, "outcome" to "uninstalled", "protectedName" to "부모님", "appLabel" to "초고속 클리너"),
            FakePusher.sent.single().data,
        )
        // 같은 결과 재전송은 200, 푸시 없음. 다른 결과는 409
        assertEquals(HttpStatusCode.OK, outcome(a.id, "uninstalled").status)
        assertEquals(1, FakePusher.sent.size)
        outcome(a.id, "declined").problem(HttpStatusCode.Conflict, "alert-closed")
    }

    @Test
    fun `응답 전에는 먼저 지운 경우만, 보호자는 403`() = serverTest {
        family()
        val a = created()
        outcome(a.id, "uninstalled").problem(HttpStatusCode.Conflict, "not-decided")
        outcome(a.id, "already_removed", GUARDIAN).problem(HttpStatusCode.Forbidden, "forbidden")
        assertEquals("resolved", outcome(a.id, "already_removed").json().s("status"))
        // 끝난 요청에는 응답 불가
        decide(a.id).problem(HttpStatusCode.Conflict, "alert-closed")
        // 같은 앱을 다시 보내면 새 요청
        assertNotEquals(a.id, created().id)
    }
}

class AlertRetentionTest {
    @Test
    fun `유일한 연결을 끊으면 요청도 바로 삭제`() = serverTest {
        val (link) = family()
        created()
        assertEquals(HttpStatusCode.NoContent, client.api(Delete, "/links/$link", SENIOR).status)
        assertEquals(0L, transaction(TestDb.db) { Alerts.selectAll().count() })
    }

    @Test
    fun `다른 보호자도 받은 요청은 남고 끊은 보호자만 못 봄`() = serverTest {
        val (l1, l2) = family(GUARDIAN, G2)
        val a = created()
        assertEquals(HttpStatusCode.NoContent, client.api(Delete, "/links/$l1", GUARDIAN).status)
        assertEquals(listOf(l2), client.api(Get, "/alerts/${a.id}", SENIOR).json().ids())
        client.api(Get, "/alerts/${a.id}", GUARDIAN).problem(HttpStatusCode.NotFound, "not-found")
    }

    @Test
    fun `보호자 기기를 지우면 그 보호자만 받은 요청도 삭제`() = serverTest {
        family()
        created()
        val g = client.api(Get, "/links", GUARDIAN).json()["items"]!!.jsonArray[0].jsonObject.o("guardianDevice").s("deviceId")
        assertEquals(HttpStatusCode.NoContent, client.api(Delete, "/devices/$g", GUARDIAN).status)
        assertEquals(0L, transaction(TestDb.db) { Alerts.selectAll().count() })
    }
}

/** PR #14 리뷰에서 나온 경합·노출 경우 */
class AlertReviewFollowupTest {
    @Test
    fun `증상 근거가 있는 열린 요청에 주의 판정이 와도 뒤에 연결된 보호자는 받음`() = serverTest {
        val (l1) = family(GUARDIAN)
        val a = created(symptom())
        // 요청 뒤에 G2 연결 (주의 단계 받기 꺼짐)
        val code = client.api(Post, "/pairing-codes", SENIOR).json().s("code")!!
        reg(G2, "guardian")
        val l2 = client.api(Post, "/pairing-codes/$code/accept", G2).json().s("linkId")!!
        client.api(Post, "/links/$l2/confirm", SENIOR)
        FakePusher.sent.clear()
        val merged = create(scan(level = "CAUTION")).contract("Alert")
        assertEquals(a.id, merged.id)
        assertEquals(listOf(l1, l2).sorted(), merged.ids())
        assertEquals("ALERT_CREATED", FakePusher.sent.single { it.token == "fcm-guardian2" }.data["type"])
    }

    @Test
    fun `응답과 새 근거가 동시에 와도 응답 뒤에 근거가 끼지 않음`() = serverTest { clock ->
        family()
        repeat(8) { round ->
            val a = created(scan(versionCode = round + 1))
            val body = symptom(count = 12 + round).replace("\"versionCode\":1", "\"versionCode\":${round + 1}")
            val (d, _) = coroutineScope {
                val d = async { decide(a.id) }
                val c = async { create(body) }
                d.await() to c.await()
            }
            assertEquals(HttpStatusCode.OK, d.status, d.bodyAsText())
            val decidedView = d.json()
            val final = client.api(Get, "/alerts/${a.id}", SENIOR).json()
            // 응답 시점에 없던 증상 근거가 결정된 요청에 붙었으면 위반 (그 근거는 새 요청으로 가야 함)
            assertEquals(decidedView["symptom"] != null, final["symptom"] != null, "round $round")
            clock.instant = clock.instant.plusSeconds(1)
        }
    }

    @Test
    fun `연결 해제와 요청 생성이 동시에 와도 해제된 연결에 요청이 남지 않음`() = serverTest {
        repeat(8) { round ->
            TestDb.reset()
            val (link) = family()
            coroutineScope {
                listOf(async { client.api(Delete, "/links/$link", SENIOR) }, async { create(scan()) }).awaitAll()
            }
            assertEquals(0L, transaction(TestDb.db) { Alerts.selectAll().count() }, "round $round")
            assertEquals(0L, transaction(TestDb.db) { AlertLinks.selectAll().count() }, "round $round")
        }
    }

    @Test
    fun `두 보호자가 동시에 끊으면 요청은 삭제`() = serverTest {
        repeat(8) { round ->
            TestDb.reset()
            val (l1, l2) = family(GUARDIAN, G2)
            created()
            coroutineScope {
                listOf(async { client.api(Delete, "/links/$l1", GUARDIAN) }, async { client.api(Delete, "/links/$l2", G2) }).awaitAll()
            }
            assertEquals(0L, transaction(TestDb.db) { Alerts.selectAll().count() }, "round $round")
        }
    }

    @Test
    fun `연결을 끊은 보호자는 같은 키로 다시 보내도 저장된 요청 내용을 못 받음`() = serverTest {
        val (l1, _) = family(GUARDIAN, G2)
        val a = created()
        val key = UUID.randomUUID().toString()
        assertEquals(HttpStatusCode.OK, client.api(Post, "/alerts/${a.id}/decision", GUARDIAN, """{"decision":"recommend_uninstall"}""", key = key).status)
        // 다른 보호자가 남아 요청은 남지만, 끊은 보호자의 저장 응답은 지워짐
        client.api(Delete, "/links/$l1", GUARDIAN)
        val replay = client.api(Post, "/alerts/${a.id}/decision", GUARDIAN, """{"decision":"recommend_uninstall"}""", key = key)
        assertNull(replay.headers["Idempotent-Replayed"])
        replay.problem(HttpStatusCode.Forbidden, "forbidden")
    }

    @Test
    fun `요청이 지워지면 그 요청을 담은 저장 응답도 지워짐`() = serverTest {
        val (link) = family()
        val key = UUID.randomUUID().toString()
        val a = create(scan(), key).json()
        client.api(Delete, "/links/$link", SENIOR)
        assertEquals(0L, transaction(TestDb.db) { IdempotencyKeys.selectAll().where { IdempotencyKeys.alertId eq a.id }.count() })
        // 같은 키 재전송은 지금 상태로 새로 판단 (연결 없음 409), 지워진 요청을 돌려주지 않음
        create(scan(), key).problem(HttpStatusCode.Conflict, "no-active-link")
    }

    @Test
    fun `기기를 지우면 그 기기의 저장 응답도 지워짐`() = serverTest {
        val key = UUID.randomUUID().toString()
        val first = client.api(Post, "/devices", SENIOR, """{"role":"protected","fcmToken":"f","appVersionCode":1,"sdkInt":31}""", key = key).json()
        client.api(Delete, "/devices/${first.s("deviceId")}", SENIOR)
        val again = client.api(Post, "/devices", SENIOR, """{"role":"protected","fcmToken":"f","appVersionCode":1,"sdkInt":31}""", key = key)
        assertNull(again.headers["Idempotent-Replayed"])
        assertNotEquals(first.s("deviceId"), again.json().s("deviceId"))
    }
}
