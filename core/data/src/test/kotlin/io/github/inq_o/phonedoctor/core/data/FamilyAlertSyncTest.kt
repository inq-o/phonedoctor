package io.github.inq_o.phonedoctor.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.inq_o.phonedoctor.core.database.AlertLocalDao
import io.github.inq_o.phonedoctor.core.database.AlertLocalEntity
import io.github.inq_o.phonedoctor.core.database.AllowEntryDao
import io.github.inq_o.phonedoctor.core.database.AllowEntryEntity
import io.github.inq_o.phonedoctor.core.database.ScanRunDao
import io.github.inq_o.phonedoctor.core.database.ScanRunEntity
import io.github.inq_o.phonedoctor.core.datastore.RegisteredDevice
import io.github.inq_o.phonedoctor.core.datastore.UserPreferences
import io.github.inq_o.phonedoctor.core.model.AlertOutcome
import io.github.inq_o.phonedoctor.core.model.AlertStatus
import io.github.inq_o.phonedoctor.core.model.AppIdentity
import io.github.inq_o.phonedoctor.core.model.FamilyDecision
import io.github.inq_o.phonedoctor.core.network.ApiException
import io.github.inq_o.phonedoctor.core.network.PhoneDoctorNetworkDataSource
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlert
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertCreate
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertKind
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertList
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertSource
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertStatus
import io.github.inq_o.phonedoctor.core.network.model.NetworkDecision
import io.github.inq_o.phonedoctor.core.network.model.NetworkDecisionCreate
import io.github.inq_o.phonedoctor.core.network.model.NetworkDecisionType
import io.github.inq_o.phonedoctor.core.network.model.NetworkDevice
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceRegistration
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceStatus
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceSummary
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceUpdate
import io.github.inq_o.phonedoctor.core.network.model.NetworkInstallSource
import io.github.inq_o.phonedoctor.core.network.model.NetworkLink
import io.github.inq_o.phonedoctor.core.network.model.NetworkLinkStatus
import io.github.inq_o.phonedoctor.core.network.model.NetworkOutcome
import io.github.inq_o.phonedoctor.core.network.model.NetworkOutcomeCreate
import io.github.inq_o.phonedoctor.core.network.model.NetworkOutcomeType
import io.github.inq_o.phonedoctor.core.network.model.NetworkPairingCode
import io.github.inq_o.phonedoctor.core.packages.AppDetails
import io.github.inq_o.phonedoctor.core.packages.InstalledApp
import io.github.inq_o.phonedoctor.core.packages.InstalledApps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class FamilyAlertSyncTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val alerts = FakeAlertDao()
    private val allow = FakeAllowDao()
    private val scans = FakeScanRunDao()
    private val server = FakeServer()
    private var scheduled = 0
    private var clock = 1_000_000_000L
    private lateinit var prefs: UserPreferences
    private var stores = 0

    private suspend fun TestScope.sync(registered: String? = "SENIOR", available: Boolean = true): DefaultFamilyAlertSync {
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))) {
            tmp.newFile("prefs${++stores}.preferences_pb").also { it.delete() }
        }
        prefs = UserPreferences(store)
        registered?.let { prefs.setRegisteredDevice(RegisteredDevice("dev-1", it)) }
        return DefaultFamilyAlertSync(
            available, server, prefs, alerts, allow, scans, FakeApps,
            { ProtectionState(notifications = true, usageAccess = false, notificationListener = false) },
            { scheduled++ },
        ).also { it.now = { clock } }
    }

    private fun openAlert(id: Long = 1, date: String = "2026-11-23", count: Int = 5) = AlertLocalEntity(
        id = id, packageName = "com.ad.cleaner", signingCertSha256 = "ab".repeat(32), versionCode = 7, label = "초고속 클리너",
        source = "symptom", status = AlertStatus.OPEN.name, outcome = null, date = date, count = count, days = 1,
        firstAt = 1_000, lastAt = 2_000, detectorVersion = "usage-test", openedDate = date, createdAt = 500, updatedAt = 600,
        needsSync = true,
    ).also { alerts.rows[id] = it }

    @Test
    fun `등록하지 않았거나 보호자 폰이거나 꺼진 빌드면 보내지 않는다`() = runTest {
        openAlert()
        for (s in listOf(sync(registered = null), sync(registered = "GUARDIAN"), sync(available = false))) {
            assertTrue(s.sync().complete)
        }
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `열린 요청을 보내고 같은 근거는 다시 보내지 않는다`() = runTest {
        openAlert()
        val s = sync()
        assertTrue(s.sync().complete)
        val body = server.created.single()
        assertEquals(NetworkAlertSource.SYMPTOM, body.source)
        assertEquals(NetworkAlertKind.APPROVAL, body.kind)
        assertEquals("com.ad.cleaner", body.app.packageName)
        assertEquals(NetworkInstallSource.BROWSER_OR_FILE, body.app.installSource)
        assertEquals(5, body.symptom?.count)
        assertEquals("1970-01-01T00:00:00.500Z", body.detectedAt)
        val row = alerts.rows.getValue(1)
        assertEquals("srv-1", row.serverAlertId)
        assertFalse(row.needsSync)

        alerts.rows[1] = row.copy(needsSync = true, updatedAt = 700)
        s.sync()
        assertEquals(1, server.created.size)
    }

    @Test
    fun `근거가 늘면 다시 보내지만 보호자가 답한 뒤에는 보내지 않는다`() = runTest {
        openAlert()
        val s = sync()
        s.sync()
        alerts.rows[1] = alerts.rows.getValue(1).copy(count = 9, needsSync = true, updatedAt = 700)
        s.sync()
        assertEquals(listOf(5, 9), server.created.map { it.symptom?.count })

        server.decide("srv-1", NetworkDecisionType.RECOMMEND_UNINSTALL)
        s.sync()
        alerts.rows[1] = alerts.rows.getValue(1).copy(count = 12, needsSync = true, updatedAt = 800)
        s.sync()
        // 응답 뒤에 보내면 서버가 새 요청을 만든다
        assertEquals(2, server.created.size)
    }

    @Test
    fun `받을 보호자가 없으면 표시만 지우고 망 문제면 다시 시도한다`() = runTest {
        openAlert()
        val s = sync()
        server.fail["createAlert"] = ApiException(409, "no-active-link", null, null, null, null)
        assertTrue(s.sync().complete)
        assertNull(alerts.rows.getValue(1).serverAlertId)
        assertFalse(alerts.rows.getValue(1).needsSync)

        alerts.rows[1] = alerts.rows.getValue(1).copy(needsSync = true, updatedAt = 700)
        server.fail["createAlert"] = IOException("offline")
        assertFalse(s.sync().complete)
        assertTrue(alerts.rows.getValue(1).needsSync)
    }

    @Test
    fun `보내는 동안 새 근거가 생기면 표시를 남긴다`() = runTest {
        openAlert()
        val s = sync()
        server.onCreate = { alerts.rows[1] = alerts.rows.getValue(1).copy(count = 8, updatedAt = 900) }
        s.sync()
        assertTrue(alerts.rows.getValue(1).needsSync)
    }

    @Test
    fun `놓친 응답을 받아 와 한 번만 알린다`() = runTest {
        openAlert()
        val s = sync()
        s.sync()
        server.decide("srv-1", NetworkDecisionType.RECOMMEND_UNINSTALL, message = "엄마 이거 광고 앱이에요")
        val first = s.sync().decisions.single()
        assertEquals(FamilyDecision.RECOMMEND_UNINSTALL, first.decision)
        assertEquals("민지", first.guardianName)
        assertTrue(first.notify)
        assertEquals("엄마 이거 광고 앱이에요", first.alert.family?.messageToSenior)
        // 푸시로 이미 안 응답은 다시 알리지 않는다
        assertNull(s.applyDecision("srv-1", "recommend_uninstall", "민지", null))
        assertTrue(s.sync().decisions.isEmpty())
    }

    @Test
    fun `괜찮다고 하면 허용 목록에 넣고 허용함 결과를 보낸다`() = runTest {
        openAlert()
        val s = sync()
        s.sync()
        server.decide("srv-1", NetworkDecisionType.ALLOW)
        val applied = s.applyDecision("srv-1", "allow", "민지", null)!!
        assertTrue(applied.notify)
        assertEquals(AppIdentity("com.ad.cleaner", "ab".repeat(32), 7), allow.entries.single().let { AppIdentity(it.packageName, it.signingCertSha256, it.versionCode) })
        val row = alerts.rows.getValue(1)
        assertEquals(AlertStatus.RESOLVED.name, row.status)
        assertEquals(AlertOutcome.ALLOWED.name, row.outcome)

        s.sync()
        assertEquals(listOf(NetworkOutcomeType.ALLOWED), server.outcomes.map { it.second.outcome })
        assertTrue(alerts.rows.getValue(1).outcomeSent)
    }

    @Test
    fun `답 전에 지우면 already_removed, 지우자고 한 뒤 지우면 uninstalled`() = runTest {
        openAlert(1)
        openAlert(2).also { alerts.rows[2] = it.copy(packageName = "com.ad.other") }
        val s = sync()
        s.sync()
        server.decide("srv-2", NetworkDecisionType.RECOMMEND_UNINSTALL)
        for (id in listOf(1L, 2L)) {
            alerts.rows[id] = alerts.rows.getValue(id).copy(status = AlertStatus.RESOLVED.name, outcome = AlertOutcome.UNINSTALLED.name, needsSync = true, updatedAt = 900)
        }
        s.sync()
        assertEquals(
            listOf("srv-1" to NetworkOutcomeType.ALREADY_REMOVED, "srv-2" to NetworkOutcomeType.UNINSTALLED),
            server.outcomes.map { it.first to it.second.outcome },
        )
        // 결과는 한 번만
        alerts.rows[1] = alerts.rows.getValue(1).copy(needsSync = true, updatedAt = 1000)
        s.sync()
        assertEquals(2, server.outcomes.size)
    }

    @Test
    fun `서버가 결과를 거절하면 다시 보내지 않는다`() = runTest {
        openAlert()
        val s = sync()
        s.sync()
        alerts.rows[1] = alerts.rows.getValue(1).copy(status = AlertStatus.RESOLVED.name, outcome = AlertOutcome.UNINSTALLED.name, needsSync = true, updatedAt = 900)
        server.fail["reportOutcome"] = ApiException(409, "alert-closed", null, null, null, null)
        assertTrue(s.sync().complete)
        assertTrue(alerts.rows.getValue(1).outcomeSent)
    }

    @Test
    fun `알림 전용은 kind notice로 보내고 결과는 보내지 않는다`() = runTest {
        openAlert().also {
            alerts.rows[1] = it.copy(kind = "notice", status = AlertStatus.RESOLVED.name, outcome = AlertOutcome.ALLOWED.name, outcomeSent = true)
        }
        sync().sync()
        assertEquals(NetworkAlertKind.NOTICE, server.created.single().kind)
        assertTrue(server.outcomes.isEmpty())
    }

    @Test
    fun `서버 요청이 만료됐는데 또 광고를 띄우면 새 요청으로 보낸다`() = runTest {
        openAlert()
        val s = sync()
        s.sync()
        server.expire("srv-1")
        s.sync()
        alerts.rows[1] = alerts.rows.getValue(1).copy(count = 7, needsSync = true, updatedAt = 900)
        s.sync()
        assertEquals("srv-2", alerts.rows.getValue(1).serverAlertId)
    }

    @Test
    fun `상태는 6시간마다 마지막 검사 시각으로 알린다`() = runTest {
        val s = sync()
        s.sync()
        assertTrue(server.statuses.isEmpty()) // 검사 기록이 없으면 알릴 것이 없다
        scans.latest = 1_700_000_000_000
        s.sync()
        clock += 5 * 3_600_000L
        s.sync()
        clock += 3_600_000L
        s.sync()
        assertEquals(2, server.statuses.size)
        assertEquals("2023-11-14T22:13:20Z", server.statuses.first().checkedAt)
        assertFalse(server.statuses.first().protection.usageAccess)
    }

    @Test
    fun `서버가 기기를 지웠으면 등록과 서버 사본을 비운다`() = runTest {
        openAlert()
        val s = sync()
        s.sync()
        alerts.rows[1] = alerts.rows.getValue(1).copy(count = 9, needsSync = true, updatedAt = 900)
        server.fail["listAlerts"] = ApiException(403, "device-not-registered", null, null, null, null)
        s.sync()
        assertNull(prefs.registeredDevice.first())
        assertNull(alerts.rows.getValue(1).serverAlertId)
    }

    @Test
    fun `새 보호자와 연결하면 열린 요청을 다시 보내게 한다`() = runTest {
        openAlert().also { alerts.rows[1] = it.copy(needsSync = false) }
        val s = sync()
        s.onLinkConfirmed()
        assertTrue(alerts.rows.getValue(1).needsSync)
        assertEquals(1, scheduled)
    }

    @Test
    fun `결과 대응표`() {
        assertEquals(NetworkOutcomeType.ALREADY_REMOVED, outcomeToSend(AlertOutcome.UNINSTALLED, SERVER_OPEN, null))
        assertEquals(NetworkOutcomeType.ALREADY_REMOVED, outcomeToSend(AlertOutcome.VERSION_CHANGED, SERVER_OPEN, null))
        assertNull(outcomeToSend(AlertOutcome.DECLINED, SERVER_OPEN, null))
        assertEquals(NetworkOutcomeType.DECLINED, outcomeToSend(AlertOutcome.DECLINED, SERVER_DECIDED, FamilyDecision.RECOMMEND_UNINSTALL))
        assertNull(outcomeToSend(AlertOutcome.FAILED, SERVER_DECIDED, FamilyDecision.RECOMMEND_UNINSTALL))
        assertEquals(NetworkOutcomeType.ALREADY_REMOVED, outcomeToSend(AlertOutcome.UNINSTALLED, SERVER_DECIDED, FamilyDecision.ALLOW))
        assertNull(outcomeToSend(AlertOutcome.ALLOWED, SERVER_OPEN, null))
        assertNull(outcomeToSend(AlertOutcome.UNINSTALLED, SERVER_RESOLVED, FamilyDecision.RECOMMEND_UNINSTALL))
        assertNull(outcomeToSend(AlertOutcome.UNINSTALLED, SERVER_EXPIRED, null))
    }

    @Test
    fun `설치 경로 대응`() {
        assertEquals(NetworkInstallSource.GOOGLE_PLAY, installSource("com.android.vending"))
        assertEquals(NetworkInstallSource.ONE_STORE, installSource("com.skt.skaf.A000Z00040"))
        assertEquals(NetworkInstallSource.GALAXY_STORE, installSource("com.sec.android.app.samsungapps"))
        assertEquals(NetworkInstallSource.ADB, installSource("com.android.shell"))
        assertEquals(NetworkInstallSource.UNKNOWN, installSource(null))
        assertEquals(NetworkInstallSource.OTHER_STORE, installSource("com.example.store"))
    }
}

private object FakeApps : InstalledApps {
    override val ownPackageName = "io.github.inq_o.phonedoctor"
    override fun snapshot(): Map<String, InstalledApp> = emptyMap()
    override fun identity(packageName: String): AppIdentity? = null
    override fun label(packageName: String): String? = null
    override fun exportedActivities(packageName: String): Set<String>? = null
    override fun isInstalled(packageName: String): Boolean? = true
    override fun details(packageName: String) = AppDetails("1.0", "com.android.packageinstaller", 100, 200)
}

private class FakeAlertDao : AlertLocalDao {
    val rows = sortedMapOf<Long, AlertLocalEntity>()
    private fun edit(id: Long, f: (AlertLocalEntity) -> AlertLocalEntity) {
        rows[id]?.let { rows[id] = f(it) }
    }

    override suspend fun insert(alert: AlertLocalEntity): Long {
        val id = (rows.keys.maxOrNull() ?: 0) + 1
        rows[id] = alert.copy(id = id)
        return id
    }
    override suspend fun update(alert: AlertLocalEntity) {
        rows[alert.id] = alert
    }
    override suspend fun find(packageName: String, cert: String, versionCode: Long) =
        rows.values.firstOrNull { it.packageName == packageName && it.signingCertSha256 == cert && it.versionCode == versionCode }
    override suspend fun get(id: Long) = rows[id]
    override fun observe(id: Long): Flow<AlertLocalEntity?> = flowOf(rows[id])
    override fun observeOpen(): Flow<List<AlertLocalEntity>> = flowOf(emptyList())
    override suspend fun open() = rows.values.filter { it.status == "OPEN" }
    override suspend fun all() = rows.values.toList()
    override suspend fun pendingSync() = rows.values.filter { it.needsSync }
    override suspend fun findByServerId(serverAlertId: String) = rows.values.firstOrNull { it.serverAlertId == serverAlertId }
    override suspend fun markSent(id: Long, serverAlertId: String, date: String, count: Int, serverStatus: String) =
        edit(id) { it.copy(serverAlertId = serverAlertId, sentDate = date, sentCount = count, serverStatus = serverStatus) }
    override suspend fun setServerStatus(id: Long, serverStatus: String) = edit(id) { it.copy(serverStatus = serverStatus) }
    override suspend fun setDecision(id: Long, serverStatus: String, decision: String, guardianName: String?, messageToSenior: String?) =
        edit(id) { it.copy(serverStatus = serverStatus, decision = decision, guardianName = guardianName, messageToSenior = messageToSenior ?: it.messageToSenior) }
    override suspend fun markOutcomeSent(id: Long) = edit(id) { it.copy(outcomeSent = true) }
    override suspend fun clearNeedsSync(id: Long, seenUpdatedAt: Long) = edit(id) { if (it.updatedAt == seenUpdatedAt) it.copy(needsSync = false) else it }
    override suspend fun markOpenForSync() {
        rows.keys.toList().forEach { id -> edit(id) { if (it.status == "OPEN") it.copy(needsSync = true) else it } }
    }
    override suspend fun forgetServer() {
        rows.keys.toList().forEach { id ->
            edit(id) {
                it.copy(
                    serverAlertId = null, sentDate = null, sentCount = null, serverStatus = null, decision = null, guardianName = null,
                    messageToSenior = null, outcomeSent = false, needsSync = false,
                )
            }
        }
    }
}

private class FakeAllowDao : AllowEntryDao {
    val entries = mutableListOf<AllowEntryEntity>()
    override suspend fun insert(entry: AllowEntryEntity) {
        if (entries.none { it.packageName == entry.packageName && it.versionCode == entry.versionCode }) entries += entry
    }
    override suspend fun isAllowed(packageName: String, cert: String, versionCode: Long) =
        entries.any { it.packageName == packageName && it.signingCertSha256 == cert && it.versionCode == versionCode }
}

private class FakeScanRunDao : ScanRunDao {
    var latest: Long? = null
    override suspend fun insert(run: ScanRunEntity) = Unit
    override fun observeRecent(limit: Int): Flow<List<ScanRunEntity>> = flowOf(emptyList())
    override fun observeLatest(result: String): Flow<ScanRunEntity?> = flowOf(null)
    override suspend fun latestStartedAt() = latest
    override suspend fun deleteOlderThan(before: Long) = Unit
}

/** 서버처럼: 같은 앱의 열린 요청은 합치고, 응답이 있으면 새 요청을 만든다 */
private class FakeServer : PhoneDoctorNetworkDataSource {
    val calls = mutableListOf<String>()
    val fail = mutableMapOf<String, Exception>()
    val created = mutableListOf<NetworkAlertCreate>()
    val outcomes = mutableListOf<Pair<String, NetworkOutcomeCreate>>()
    val statuses = mutableListOf<NetworkDeviceStatus>()
    var onCreate: () -> Unit = {}
    private val alerts = linkedMapOf<String, NetworkAlert>()
    private var seq = 0
    private var tick = 0

    private fun record(op: String) {
        calls += op
        fail.remove(op)?.let { throw it }
    }

    private fun stamp() = "2026-11-23T00:00:%02dZ".format(++tick % 60)

    fun decide(id: String, type: NetworkDecisionType, message: String? = null) {
        val a = alerts.getValue(id)
        alerts[id] = a.copy(
            status = NetworkAlertStatus.DECIDED,
            decision = NetworkDecision(type, message, NetworkDeviceSummary("g-1", "민지"), "2026-11-23T01:00:00Z"),
            updatedAt = stamp(),
        )
    }

    fun expire(id: String) {
        alerts[id] = alerts.getValue(id).copy(status = NetworkAlertStatus.EXPIRED, updatedAt = stamp())
    }

    override suspend fun createAlert(key: String, body: NetworkAlertCreate): NetworkAlert {
        record("createAlert")
        onCreate()
        created += body
        val open = alerts.values.firstOrNull { it.app.packageName == body.app.packageName && it.status == NetworkAlertStatus.OPEN }
        val alert = open?.copy(symptom = body.symptom, updatedAt = stamp()) ?: NetworkAlert(
            alertId = "srv-${++seq}", linkIds = listOf("link-1"), status = NetworkAlertStatus.OPEN, source = body.source, kind = body.kind,
            app = body.app, symptom = body.symptom, detectedAt = body.detectedAt, createdAt = stamp(), updatedAt = stamp(),
        )
        alerts[alert.alertId] = alert
        return alert
    }

    override suspend fun listAlerts(status: NetworkAlertStatus?, updatedSince: String?, pageToken: String?, pageSize: Int?): NetworkAlertList {
        record("listAlerts")
        return NetworkAlertList(alerts.values.filter { updatedSince == null || (it.updatedAt ?: it.createdAt) >= updatedSince })
    }

    override suspend fun getAlert(alertId: String): NetworkAlert {
        record("getAlert")
        return alerts[alertId] ?: throw ApiException(404, "not-found", null, null, null, null)
    }

    override suspend fun reportOutcome(key: String, alertId: String, body: NetworkOutcomeCreate): NetworkAlert {
        record("reportOutcome")
        outcomes += alertId to body
        val a = alerts.getValue(alertId).copy(status = NetworkAlertStatus.RESOLVED, outcome = NetworkOutcome(body.outcome, body.occurredAt), updatedAt = stamp())
        alerts[alertId] = a
        return a
    }

    override suspend fun reportStatus(deviceId: String, body: NetworkDeviceStatus) {
        record("reportStatus")
        statuses += body
    }

    override suspend fun registerDevice(key: String, body: NetworkDeviceRegistration): NetworkDevice = TODO()
    override suspend fun updateDevice(deviceId: String, body: NetworkDeviceUpdate): NetworkDevice = TODO()
    override suspend fun deleteDevice(deviceId: String) = TODO()
    override suspend fun createPairingCode(key: String): NetworkPairingCode = TODO()
    override suspend fun acceptPairingCode(key: String, code: String): NetworkLink = TODO()
    override suspend fun listLinks(status: NetworkLinkStatus?): List<NetworkLink> = TODO()
    override suspend fun confirmLink(key: String, linkId: String): NetworkLink = TODO()
    override suspend fun revokeLink(linkId: String) = TODO()
    override suspend fun decideAlert(key: String, alertId: String, body: NetworkDecisionCreate): NetworkAlert = TODO()
}
