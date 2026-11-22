package io.github.inq_o.phonedoctor.core.network

import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertCreate
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertKind
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertSource
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertStatus
import io.github.inq_o.phonedoctor.core.network.model.NetworkAppInfo
import io.github.inq_o.phonedoctor.core.network.model.NetworkDecisionCreate
import io.github.inq_o.phonedoctor.core.network.model.NetworkDecisionType
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceRegistration
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceRole
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceStatus
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceUpdate
import io.github.inq_o.phonedoctor.core.network.model.NetworkInstallSource
import io.github.inq_o.phonedoctor.core.network.model.NetworkLinkStatus
import io.github.inq_o.phonedoctor.core.network.model.NetworkOutcomeCreate
import io.github.inq_o.phonedoctor.core.network.model.NetworkOutcomeType
import io.github.inq_o.phonedoctor.core.network.model.NetworkProtectionState
import io.github.inq_o.phonedoctor.core.network.model.NetworkReason
import io.github.inq_o.phonedoctor.core.network.model.NetworkRiskLevel
import io.github.inq_o.phonedoctor.core.network.model.NetworkSymptomEvidence
import io.github.inq_o.phonedoctor.core.network.model.NetworkVerdict
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CERT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

private val app = NetworkAppInfo(
    packageName = "com.example.cleaner",
    appLabel = "초고속 클리너",
    versionCode = 3,
    signingCertSha256 = CERT,
    installSource = NetworkInstallSource.BROWSER_OR_FILE,
    firstInstallTime = "2026-11-20T01:00:00Z",
)

private val scan = NetworkAlertCreate(
    source = NetworkAlertSource.INSTALL_SCAN,
    app = app,
    verdict = NetworkVerdict(
        NetworkRiskLevel.RISK, 0.91, "2026.11.1-int8",
        listOf(NetworkReason("AD_SDK_HEAVY", 0.6, buildJsonObject { put("sdks", "AdMob") })),
    ),
    detectedAt = "2026-11-21T02:59:00Z",
)

private val symptom = NetworkAlertCreate(
    source = NetworkAlertSource.SYMPTOM,
    kind = NetworkAlertKind.NOTICE,
    app = app,
    symptom = NetworkSymptomEvidence("2026-11-21", 12, 1, "2026-11-21T01:00:00Z", "2026-11-21T02:00:00Z", "usage-v1"),
    detectedAt = "2026-11-21T02:00:00Z",
)

/** 서버가 돌려주는 모양 그대로 (server AlertsTest 응답과 같은 필드) */
private val ALERT_JSON = """{"alertId":"a1","protectedDevice":{"deviceId":"d1","displayName":"부모님"},"linkIds":["l1"],"status":"decided","source":"install_scan","kind":"approval",
"app":{"packageName":"com.example.cleaner","appLabel":"초고속 클리너","versionCode":3,"signingCertSha256":"$CERT","installSource":"BROWSER_OR_FILE","firstInstallTime":"2026-11-20T01:00:00Z"},
"verdict":{"level":"RISK","score":0.91,"modelVersion":"m1","reasons":[{"code":"AD_SDK_HEAVY","weight":0.6,"evidence":{"sdks":["AdMob"]}}]},
"decision":{"decision":"recommend_uninstall","messageToSenior":"엄마 이거 광고 앱이에요","decidedBy":{"deviceId":"g1","displayName":"딸"},"decidedAt":"2026-11-21T04:00:00Z"},
"detectedAt":"2026-11-21T02:59:00Z","createdAt":"2026-11-21T03:00:00Z","updatedAt":"2026-11-21T04:00:00Z","futureField":"무시"}"""

/** 앱이 보내는 본문이 계약을 지키는지. 서버 시험은 서버 응답을, 이 시험은 앱 요청을 대조한다 */
class RequestContractTest {
    private fun <T> check(schema: String, serializer: KSerializer<T>, value: T) =
        Contract.check(schema, networkJson.encodeToString(serializer, value))

    @Test
    fun `요청 본문이 openapi 스키마를 지킴`() {
        check(
            "DeviceRegistration", NetworkDeviceRegistration.serializer(),
            NetworkDeviceRegistration(NetworkDeviceRole.PROTECTED, "fcm", null, 1, 31, "samsung", "ko-KR"),
        )
        check("DeviceUpdate", NetworkDeviceUpdate.serializer(), NetworkDeviceUpdate(notifyCaution = true))
        check(
            "DeviceStatus", NetworkDeviceStatus.serializer(),
            NetworkDeviceStatus("2026-11-21T02:00:00Z", NetworkProtectionState(true, false, true)),
        )
        check("AlertCreate", NetworkAlertCreate.serializer(), scan)
        check("AlertCreate", NetworkAlertCreate.serializer(), symptom)
        check("DecisionCreate", NetworkDecisionCreate.serializer(), NetworkDecisionCreate(NetworkDecisionType.ALLOW, "괜찮아요"))
        check("OutcomeCreate", NetworkOutcomeCreate.serializer(), NetworkOutcomeCreate(NetworkOutcomeType.ALREADY_REMOVED, "2026-11-21T05:00:00Z"))
    }

    @Test
    fun `null 필드는 보내지 않고 기본값 kind는 보냄`() {
        val body = networkJson.parseToJsonElement(networkJson.encodeToString(NetworkAlertCreate.serializer(), scan)).jsonObject
        assertNull(body["symptom"])
        assertNull(body["app"]!!.jsonObject["versionName"])
        // kind 기본값(approval)은 보내지 않아도 서버 기본값과 같다
        assertTrue(body["kind"] == null || body["kind"]!!.jsonPrimitive.content == "approval")
    }

    @Test
    fun `계약 대조기가 어긋남을 잡음`() {
        // install_scan인데 verdict 없음 (if/then 조건부 필수)
        val bad = networkJson.encodeToString(NetworkAlertCreate.serializer(), scan.copy(verdict = null))
        assertTrue(Contract.errors("AlertCreate", bad).isNotEmpty())
    }
}

class RetrofitNetworkTest {
    private lateinit var server: MockWebServer
    private val tokens = FakeTokens()

    private val network get() = networkDataSource(server.url("/v1/").toString(), networkClient(tokens), networkJson)

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stop() = server.close()

    private fun respond(code: Int, body: String = "", headers: Map<String, String> = emptyMap()) {
        val type = if (code >= 400) "application/problem+json" else "application/json"
        server.enqueue(
            MockResponse.Builder().code(code).body(body).addHeader("Content-Type", type)
                .apply { headers.forEach { (k, v) -> addHeader(k, v) } }
                .build(),
        )
    }

    @Test
    fun `토큰·App Check·Idempotency-Key 헤더와 경로`() = runTest {
        respond(201, ALERT_JSON)
        network.createAlert("key-1", scan)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/alerts", req.url.encodedPath)
        assertEquals("Bearer id-0", req.headers["Authorization"])
        assertEquals("app-check", req.headers["X-Firebase-AppCheck"])
        assertEquals("key-1", req.headers["Idempotency-Key"])
        Contract.check("AlertCreate", req.body!!.utf8())
    }

    @Test
    fun `응답을 읽고 모르는 필드는 무시`() = runTest {
        Contract.check("Alert", ALERT_JSON)
        respond(200, ALERT_JSON)
        val a = network.getAlert("a1")
        assertEquals(NetworkAlertStatus.DECIDED, a.status)
        assertEquals("딸", a.decision!!.decidedBy.displayName)
        assertEquals(listOf("AdMob"), a.verdict!!.reasons[0].evidence!!["sdks"]!!.let { e -> (e as kotlinx.serialization.json.JsonArray).map { it.jsonPrimitive.content } })
        assertEquals("/v1/alerts/a1", server.takeRequest().url.encodedPath)
    }

    @Test
    fun `질의 값은 계약의 이름으로`() = runTest {
        respond(200, """{"items":[]}""")
        network.listLinks(NetworkLinkStatus.ACTIVE)
        assertEquals("active", server.takeRequest().url.queryParameter("status"))
        respond(200, """{"items":[],"nextPageToken":"t"}""")
        val page = network.listAlerts(NetworkAlertStatus.OPEN, updatedSince = "2026-11-21T00:00:00Z", pageSize = 20)
        assertEquals("t", page.nextPageToken)
        val url = server.takeRequest().url
        assertEquals("open", url.queryParameter("status"))
        assertEquals("2026-11-21T00:00:00Z", url.queryParameter("updatedSince"))
        assertNull(url.queryParameter("pageToken"))
    }

    @Test
    fun `오류는 type 끝부분과 추가 필드로`() = runTest {
        respond(
            409,
            """{"type":"https://phonedoctor.example/problems/already-decided","title":"이미 다른 응답이 있음","status":409,"detail":"딸님이 먼저 답했어요",
            "existingDecision":{"decision":"allow","decidedBy":{"deviceId":"g2","displayName":"딸"},"decidedAt":"2026-11-21T04:00:00Z"}}""",
        )
        val e = assertFailsWith<ApiException> { network.decideAlert("k", "a1", NetworkDecisionCreate(NetworkDecisionType.ALLOW)) }
        assertEquals(409, e.status)
        assertEquals("already-decided", e.slug)
        assertEquals("딸님이 먼저 답했어요", e.detail)
        assertEquals("allow", e.body!!["existingDecision"]!!.jsonObject["decision"]!!.jsonPrimitive.content)

        respond(429, """{"type":"https://phonedoctor.example/problems/too-many-attempts","title":"코드 입력 횟수 초과","status":429}""", mapOf("Retry-After" to "12"))
        val limited = assertFailsWith<ApiException> { network.acceptPairingCode("k", "123456") }
        assertEquals("too-many-attempts", limited.slug)
        assertEquals(12, limited.retryAfterSeconds)

        // 본문이 문제 형식이 아니어도(프록시 오류 등) 상태 코드는 남김
        respond(502, "Bad Gateway")
        val gateway = assertFailsWith<ApiException> { network.listLinks() }
        assertEquals(502, gateway.status)
        assertNull(gateway.slug)
    }

    @Test
    fun `ID 토큰이 만료로 거절되면 새 토큰으로 한 번만 다시`() = runTest {
        respond(401, """{"type":"https://phonedoctor.example/problems/id-token-invalid","title":"인증 실패","status":401}""")
        respond(200, """{"items":[]}""")
        network.listLinks()
        assertEquals("Bearer id-0", server.takeRequest().headers["Authorization"])
        assertEquals("Bearer id-1", server.takeRequest().headers["Authorization"])
        assertEquals(listOf(false, true), tokens.refreshes)

        // 새 토큰도 거절되면 더 시도하지 않고 오류
        respond(401, """{"type":"https://phonedoctor.example/problems/id-token-invalid","title":"인증 실패","status":401}""")
        respond(401, """{"type":"https://phonedoctor.example/problems/id-token-invalid","title":"인증 실패","status":401}""")
        assertEquals("id-token-invalid", assertFailsWith<ApiException> { network.listLinks() }.slug)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `App Check 거절은 다시 보내지 않음`() = runTest {
        respond(401, """{"type":"https://phonedoctor.example/problems/app-check-invalid","title":"인증 실패","status":401}""")
        assertEquals("app-check-invalid", assertFailsWith<ApiException> { network.listLinks() }.slug)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `본문 없는 204`() = runTest {
        respond(204)
        network.revokeLink("l1")
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertEquals("/v1/links/l1", req.url.encodedPath)
    }

    @Test
    fun `로그인 전이면 요청을 보내지 않음`() = runTest {
        tokens.signedIn = false
        assertFailsWith<NotSignedInException> { network.listLinks() }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `토큰 받기가 IOException 밖의 예외로 실패해도 IOException으로 (앱이 죽지 않게)`() = runTest {
        tokens.appCheckFailure = IllegalStateException("Play Integrity 오류")
        // OkHttp는 인터셉터의 IOException 밖 예외를 호출자에게 IOException으로 넘긴 뒤 작업 스레드에서 원래 예외를 다시 던진다(앱 종료)
        val uncaught = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, t -> uncaught += t }
        val e = try {
            assertFailsWith<IOException> { network.listLinks() }.also { Thread.sleep(300) }
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
        assertEquals(emptyList(), uncaught)
        // (코루틴 시험은 예외를 복사해 원래 예외를 cause로 붙이므로 원인 사슬에서 찾는다)
        assertTrue(generateSequence<Throwable>(e) { it.cause }.any { it.message == "Play Integrity 오류" })
        assertEquals(0, server.requestCount)
    }
}

private class FakeTokens : AuthTokens {
    val refreshes = mutableListOf<Boolean>()
    var signedIn = true
    var appCheckFailure: Exception? = null

    override suspend fun idToken(forceRefresh: Boolean): String {
        if (!signedIn) throw NotSignedInException()
        refreshes += forceRefresh
        return "id-${refreshes.count { it }}"
    }

    override suspend fun appCheckToken(): String {
        appCheckFailure?.let { throw it }
        return "app-check"
    }
}
