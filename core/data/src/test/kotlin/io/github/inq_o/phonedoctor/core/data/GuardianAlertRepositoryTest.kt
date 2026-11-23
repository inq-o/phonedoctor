package io.github.inq_o.phonedoctor.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.inq_o.phonedoctor.core.datastore.RegisteredDevice
import io.github.inq_o.phonedoctor.core.datastore.UserPreferences
import io.github.inq_o.phonedoctor.core.model.FamilyAlertStatus
import io.github.inq_o.phonedoctor.core.model.FamilyDecision
import io.github.inq_o.phonedoctor.core.model.FamilyOutcome
import io.github.inq_o.phonedoctor.core.network.ApiException
import io.github.inq_o.phonedoctor.core.network.PhoneDoctorNetworkDataSource
import io.github.inq_o.phonedoctor.core.network.ServerClock
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlert
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertCreate
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertKind
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertList
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertSource
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertStatus
import io.github.inq_o.phonedoctor.core.network.model.NetworkAppInfo
import io.github.inq_o.phonedoctor.core.network.model.NetworkDecision
import io.github.inq_o.phonedoctor.core.network.model.NetworkDecisionCreate
import io.github.inq_o.phonedoctor.core.network.model.NetworkDecisionType
import io.github.inq_o.phonedoctor.core.network.model.NetworkDevice
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceRegistration
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceStatus
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceSummary
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceUpdate
import io.github.inq_o.phonedoctor.core.network.model.NetworkLink
import io.github.inq_o.phonedoctor.core.network.model.NetworkLinkStatus
import io.github.inq_o.phonedoctor.core.network.model.NetworkOutcome
import io.github.inq_o.phonedoctor.core.network.model.NetworkOutcomeCreate
import io.github.inq_o.phonedoctor.core.network.model.NetworkOutcomeType
import io.github.inq_o.phonedoctor.core.network.model.NetworkPairingCode
import io.github.inq_o.phonedoctor.core.network.model.NetworkReason
import io.github.inq_o.phonedoctor.core.network.model.NetworkRiskLevel
import io.github.inq_o.phonedoctor.core.network.model.NetworkVerdict
import io.github.inq_o.phonedoctor.core.network.model.NetworkSymptomEvidence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant

class GuardianAlertRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val server = AlertServer()
    private var stores = 0
    private val reported = mutableListOf<Pair<String, FalsePositive>>()
    private val timing = mutableListOf<Pair<Long, Long>>() // (마감, 늦출 시간)
    private var cancelled = 0
    private val reporter = object : FalsePositiveReporter {
        override fun schedule(id: String, report: FalsePositive, generation: Long, deadline: Long, delayMillis: Long) {
            reported += id to report
            timing += deadline to delayMillis
        }

        override fun cancelAll() {
            cancelled++
        }
    }
    private lateinit var prefs: UserPreferences
    private lateinit var gate: FalsePositiveGate
    private val now = Instant.parse("2026-11-23T04:00:00Z").toEpochMilli()
    private val hour = 3_600_000L

    private suspend fun TestScope.repo(role: String? = "GUARDIAN", available: Boolean = true): DefaultGuardianAlertRepository {
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))) {
            tmp.newFile("prefs${++stores}.preferences_pb").also { it.delete() }
        }
        prefs = UserPreferences(store)
        role?.let { prefs.setRegisteredDevice(RegisteredDevice("dev-2", it)) }
        gate = FalsePositiveGate(prefs, reporter, ServerClock()).also { it.clock = { now } }
        return DefaultGuardianAlertRepository(available, server, prefs, gate)
    }

    @Test
    fun `등록 전이거나 시니어 폰이면 서버를 부르지 않는다`() = runTest {
        assertTrue(repo(role = null).alerts().isEmpty())
        assertTrue(repo(role = "SENIOR").alerts().isEmpty())
        assertTrue(repo(available = false).alerts().isEmpty())
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `여러 쪽을 이어 받고 같은 요청은 한 번만, 최근 것부터`() = runTest {
        server.pages = listOf(
            listOf(alert("a1", updated = "2026-11-23T01:00:00Z"), alert("a2", updated = "2026-11-23T02:00:00Z")),
            listOf(alert("a2", updated = "2026-11-23T02:00:00Z", status = NetworkAlertStatus.DECIDED), alert("a3", updated = "2026-11-23T00:00:00Z")),
        )
        val list = repo().alerts()
        assertEquals(listOf("a2", "a1", "a3"), list.map { it.alertId })
        assertEquals(FamilyAlertStatus.DECIDED, list.first().status)
        assertEquals("엄마", list.first().seniorName)
        assertEquals(12, list.first().symptom?.count)
        assertEquals(2, server.calls.count { it == "listAlerts" })
    }

    @Test
    fun `응답 결과와 처리 결과를 옮긴다`() = runTest {
        server.single = alert("a1", status = NetworkAlertStatus.RESOLVED).copy(
            kind = NetworkAlertKind.NOTICE,
            decision = NetworkDecision(NetworkDecisionType.RECOMMEND_UNINSTALL, "광고 앱", NetworkDeviceSummary("g-1", "민지"), "2026-11-23T03:00:00Z"),
            outcome = NetworkOutcome(NetworkOutcomeType.UNINSTALLED, "2026-11-23T04:00:00Z"),
        )
        val a = repo().alert("a1")
        assertTrue(a.notice)
        assertEquals(FamilyDecision.RECOMMEND_UNINSTALL, a.answer?.decision)
        assertEquals("민지", a.answer?.guardianName)
        assertEquals("광고 앱", a.answer?.message)
        assertEquals(FamilyOutcome.UNINSTALLED, a.outcome)
    }

    @Test
    fun `응답을 못 받으면 같은 키로 다시 보내고 한마디는 40자까지`() = runTest {
        server.single = alert("a1")
        val repo = repo()
        server.fail["decideAlert"] = IOException("offline")
        try {
            repo.decide("a1", FamilyDecision.RECOMMEND_UNINSTALL, "  엄마 이거 광고 앱이에요  ")
            fail()
        } catch (_: IOException) {
        }
        repo.decide("a1", FamilyDecision.RECOMMEND_UNINSTALL, "엄마 이거 광고 앱이에요")
        assertEquals(1, server.keys.toSet().size)
        assertEquals("엄마 이거 광고 앱이에요", server.bodies.last().messageToSenior)
        repo.decide("a1", FamilyDecision.RECOMMEND_UNINSTALL, "가".repeat(60))
        assertEquals(40, server.bodies.last().messageToSenior?.length)
        // 성공한 뒤의 새 응답은 새 키
        assertEquals(2, server.keys.toSet().size)
    }

    @Test
    fun `먼저 온 응답과 끝난 요청을 구분한다`() = runTest {
        val repo = repo()
        val existing = Json.encodeToJsonElement(
            NetworkDecision.serializer(),
            NetworkDecision(NetworkDecisionType.ALLOW, null, NetworkDeviceSummary("g-9", "철수"), "2026-11-23T03:00:00Z"),
        )
        server.fail["decideAlert"] = ApiException(409, "already-decided", null, null, JsonObject(mapOf("existingDecision" to existing)), null)
        try {
            repo.decide("a1", FamilyDecision.RECOMMEND_UNINSTALL, null)
            fail()
        } catch (e: AlreadyAnsweredException) {
            assertEquals("철수", e.answer?.guardianName)
            assertEquals(FamilyDecision.ALLOW, e.answer?.decision)
        }
        server.fail["decideAlert"] = ApiException(409, "already-decided", null, null, buildJsonObject { }, null)
        try {
            repo.decide("a1", FamilyDecision.RECOMMEND_UNINSTALL, null)
            fail()
        } catch (e: AlreadyAnsweredException) {
            assertEquals(null, e.answer)
        }
        server.fail["decideAlert"] = ApiException(409, "alert-closed", null, null, null, null)
        try {
            repo.decide("a1", FamilyDecision.RECOMMEND_UNINSTALL, null)
            fail()
        } catch (_: AlertClosedException) {
        }
    }

    @Test
    fun `먼저 들어간 답이 같은 답의 내 것이면 성공, 다른 답이면 먼저 온 답으로 알린다`() = runTest {
        server.single = alert("a1", status = NetworkAlertStatus.DECIDED)
        val mine = Json.encodeToJsonElement(
            NetworkDecision.serializer(),
            NetworkDecision(NetworkDecisionType.RECOMMEND_UNINSTALL, null, NetworkDeviceSummary("dev-2", "민지"), "2026-11-23T03:00:00Z"),
        )
        val repo = repo()
        server.fail["decideAlert"] = ApiException(409, "already-decided", null, null, JsonObject(mapOf("existingDecision" to mine)), null)
        assertEquals(FamilyAlertStatus.DECIDED, repo.decide("a1", FamilyDecision.RECOMMEND_UNINSTALL, null).status)
        server.fail["decideAlert"] = ApiException(409, "already-decided", null, null, JsonObject(mapOf("existingDecision" to mine)), null)
        try {
            repo.decide("a1", FamilyDecision.ALLOW, null)
            fail()
        } catch (e: AlreadyAnsweredException) {
            assertEquals(FamilyDecision.RECOMMEND_UNINSTALL, e.answer?.decision)
        }
    }

    @Test
    fun `쪽이 많아도 끝까지 읽어 최신 요청을 놓치지 않는다`() = runTest {
        server.pages = (0 until 12).map { listOf(alert("a$it", updated = "2026-11-23T00:%02d:00Z".format(it))) }
        val list = repo().alerts()
        assertEquals("a11", list.first().alertId)
        assertEquals(12, list.size)
    }

    @Test
    fun `열린 요청만 달라고 할 수 있다`() = runTest {
        server.pages = listOf(listOf(alert("a1")))
        repo().alerts(FamilyAlertStatus.OPEN)
        assertEquals(NetworkAlertStatus.OPEN, server.statuses.single())
    }

    @Test
    fun `판정 근거를 옮긴다`() = runTest {
        server.single = alert("a1").copy(
            source = NetworkAlertSource.INSTALL_SCAN,
            verdict = NetworkVerdict(NetworkRiskLevel.RISK, 0.94, "2026.11.1", listOf(NetworkReason("AD_SDK_HEAVY", 0.5))),
        )
        val a = repo().alert("a1")
        assertTrue(a.fromInstallScan)
        assertEquals(listOf("AD_SDK_HEAVY"), a.verdict?.reasons)
        assertEquals("RISK", a.verdict?.level)
    }

    @Test
    fun `괜찮은 앱이라고 답하면 오탐을 익명으로 한 번 알리고, 끄면 알리지 않는다`() = runTest {
        server.single = alert("a1")
        val repo = repo()
        repo.decide("a1", FamilyDecision.RECOMMEND_UNINSTALL, null)
        assertTrue(reported.isEmpty())
        repo.decide("a1", FamilyDecision.ALLOW, null)
        val (id, report) = reported.single()
        assertEquals("a1:symptom", id)
        assertEquals("com.ad", report.packageName)
        assertEquals(7L, report.versionCode)
        assertEquals("usage-test", report.detectorVersion)
        assertEquals(false, report.installScan)

        // 같은 요청에 다시 답해도 한 번만
        repo.decide("a1", FamilyDecision.ALLOW, null)
        assertEquals(1, reported.size)

        server.single = alert("a2")
        repo.setFalsePositiveFeedback(false)
        // 끄면 이미 맡긴 것도 거둔다
        assertEquals(1, cancelled)
        repo.decide("a2", FamilyDecision.ALLOW, null)
        assertEquals(1, reported.size)
        repo.setFalsePositiveFeedback(true)
        assertEquals(1, cancelled)
    }

    @Test
    fun `응답을 못 받고 앱이 다시 켜져 같은 답을 다시 보내도 신고를 맡긴다`() = runTest {
        server.single = alert("a1", status = NetworkAlertStatus.DECIDED)
        val mine = Json.encodeToJsonElement(
            NetworkDecision.serializer(),
            NetworkDecision(NetworkDecisionType.ALLOW, null, NetworkDeviceSummary("dev-2", "민지"), "2026-11-23T03:00:00Z"),
        )
        server.fail["decideAlert"] = ApiException(409, "already-decided", null, null, JsonObject(mapOf("existingDecision" to mine)), null)
        repo().decide("a1", FamilyDecision.ALLOW, null)
        assertEquals(listOf("a1:symptom"), reported.map { it.first })
        // 마감은 서버가 기록한 첫 응답 시각(03:00) + 24시간, 늦출 시간은 1시간 ~ 마감 1시간 전
        val (deadline, delay) = timing.single()
        assertEquals(Instant.parse("2026-11-24T03:00:00Z").toEpochMilli(), deadline)
        assertTrue(delay >= hour && delay <= deadline - now - hour)
    }

    @Test
    fun `오래전에 한 답을 다시 보내면 마감이 지나 맡기지 않는다`() = runTest {
        server.single = alert("a1", status = NetworkAlertStatus.DECIDED)
        val old = Json.encodeToJsonElement(
            NetworkDecision.serializer(),
            NetworkDecision(NetworkDecisionType.ALLOW, null, NetworkDeviceSummary("dev-2", "민지"), "2026-11-21T04:00:00Z"),
        )
        server.fail["decideAlert"] = ApiException(409, "already-decided", null, null, JsonObject(mapOf("existingDecision" to old)), null)
        repo().decide("a1", FamilyDecision.ALLOW, null)
        assertTrue(reported.isEmpty())
        // 마감 1시간 안쪽(1시간 넘게 늦출 수 없음)도 맡기지 않는다
        server.fail["decideAlert"] = ApiException(409, "already-decided", null, null, JsonObject(mapOf("existingDecision" to Json.encodeToJsonElement(
            NetworkDecision.serializer(),
            NetworkDecision(NetworkDecisionType.ALLOW, null, NetworkDeviceSummary("dev-2", "민지"), "2026-11-22T06:00:00Z"),
        ))), null)
        repo().decide("a1", FamilyDecision.ALLOW, null)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun `응답을 기다리는 사이 껐다 켜도 그 응답의 신고는 맡기지 않는다`() = runTest {
        server.single = alert("a1")
        val repo = repo()
        server.onDecide = {
            repo.setFalsePositiveFeedback(false)
            repo.setFalsePositiveFeedback(true)
        }
        repo.decide("a1", FamilyDecision.ALLOW, null)
        assertTrue(reported.isEmpty())
        // 다음 응답은 다시 맡긴다
        server.onDecide = null
        server.single = alert("a2")
        repo.decide("a2", FamilyDecision.ALLOW, null)
        assertEquals(listOf("a2:symptom"), reported.map { it.first })
    }

    @Test
    fun `근거가 합쳐진 요청은 근거마다 알린다`() = runTest {
        server.single = alert("a1").copy(
            verdict = NetworkVerdict(NetworkRiskLevel.CAUTION, 0.7, "2026.11.1", listOf(NetworkReason("SIDELOADED", 0.4))),
        )
        repo().decide("a1", FamilyDecision.ALLOW, null)
        assertEquals(listOf("a1:install_scan", "a1:symptom"), reported.map { it.first })
        val scan = reported.first().second
        assertEquals(listOf("SIDELOADED"), scan.reasonCodes)
        assertEquals("2026.11.1", scan.modelVersion)
        assertEquals(0.7, scan.score)
    }

    @Test
    fun `신고를 맡기다 실패해도 응답은 성공`() = runTest {
        server.single = alert("a1")
        val p = UserPreferences(
            PreferenceDataStoreFactory.create(scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))) {
                tmp.newFile("prefs-fail.preferences_pb").also { it.delete() }
            },
        ).also { it.setRegisteredDevice(RegisteredDevice("dev-2", "GUARDIAN")) }
        val failing = FalsePositiveGate(p, object : FalsePositiveReporter {
            override fun schedule(id: String, report: FalsePositive, generation: Long, deadline: Long, delayMillis: Long) = error("WorkManager 오류")
            override fun cancelAll() = Unit
        }, ServerClock())
        val repo = DefaultGuardianAlertRepository(true, server, p, failing)
        assertEquals(FamilyAlertStatus.DECIDED, repo.decide("a1", FamilyDecision.ALLOW, null).status)
    }

    @Test
    fun `서버가 지운 기기는 등록 없음으로 알린다`() = runTest {
        server.fail["listAlerts"] = ApiException(403, "device-not-registered", null, null, null, null)
        try {
            repo().alerts()
            fail()
        } catch (_: DeviceNotRegisteredException) {
        }
    }

    private fun alert(id: String, updated: String = "2026-11-23T00:00:00Z", status: NetworkAlertStatus = NetworkAlertStatus.OPEN) = NetworkAlert(
        alertId = id,
        protectedDevice = NetworkDeviceSummary("dev-1", "엄마"),
        linkIds = listOf("link-1"),
        status = status,
        source = NetworkAlertSource.SYMPTOM,
        kind = NetworkAlertKind.APPROVAL,
        app = NetworkAppInfo("com.ad", "초고속 클리너", 7, "1.0", "ab".repeat(32), firstInstallTime = "2026-11-01T00:00:00Z"),
        symptom = NetworkSymptomEvidence("2026-11-23", 12, 1, "2026-11-23T00:00:00Z", "2026-11-23T01:00:00Z", "usage-test"),
        detectedAt = "2026-11-23T00:00:00Z",
        createdAt = "2026-11-23T00:00:00Z",
        updatedAt = updated,
    )
}

private class AlertServer : PhoneDoctorNetworkDataSource {
    val calls = mutableListOf<String>()
    val fail = mutableMapOf<String, Exception>()
    val keys = mutableListOf<String>()
    val bodies = mutableListOf<NetworkDecisionCreate>()
    var pages: List<List<NetworkAlert>> = emptyList()
    var single: NetworkAlert? = null
    val statuses = mutableListOf<NetworkAlertStatus?>()
    /** 응답이 서버에 가 있는 동안 할 일 */
    var onDecide: (suspend () -> Unit)? = null

    private fun record(op: String) {
        calls += op
        fail.remove(op)?.let { throw it }
    }

    override suspend fun listAlerts(status: NetworkAlertStatus?, updatedSince: String?, pageToken: String?, pageSize: Int?): NetworkAlertList {
        record("listAlerts")
        statuses += status
        val index = pageToken?.toInt() ?: 0
        val items = pages.getOrElse(index) { emptyList() }
        return NetworkAlertList(items, if (index + 1 < pages.size) "${index + 1}" else null)
    }

    override suspend fun getAlert(alertId: String): NetworkAlert {
        record("getAlert")
        return single ?: throw ApiException(404, "not-found", null, null, null, null)
    }

    override suspend fun decideAlert(key: String, alertId: String, body: NetworkDecisionCreate): NetworkAlert {
        onDecide?.invoke()
        keys += key
        bodies += body
        record("decideAlert")
        return single!!.copy(status = NetworkAlertStatus.DECIDED)
    }

    override suspend fun registerDevice(key: String, body: NetworkDeviceRegistration): NetworkDevice = TODO()
    override suspend fun updateDevice(deviceId: String, body: NetworkDeviceUpdate): NetworkDevice = TODO()
    override suspend fun deleteDevice(deviceId: String) = TODO()
    override suspend fun reportStatus(deviceId: String, body: NetworkDeviceStatus) = TODO()
    override suspend fun createPairingCode(key: String): NetworkPairingCode = TODO()
    override suspend fun acceptPairingCode(key: String, code: String): NetworkLink = TODO()
    override suspend fun listLinks(status: NetworkLinkStatus?): List<NetworkLink> = TODO()
    override suspend fun confirmLink(key: String, linkId: String): NetworkLink = TODO()
    override suspend fun revokeLink(linkId: String) = TODO()
    override suspend fun createAlert(key: String, body: NetworkAlertCreate): NetworkAlert = TODO()
    override suspend fun reportOutcome(key: String, alertId: String, body: NetworkOutcomeCreate): NetworkAlert = TODO()
    override suspend fun reportFalsePositive(body: io.github.inq_o.phonedoctor.core.network.model.NetworkFalsePositiveReport) = Unit
}
