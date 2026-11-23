package io.github.inq_o.phonedoctor.core.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.inq_o.phonedoctor.core.database.AlertLocalDao
import io.github.inq_o.phonedoctor.core.database.AlertLocalEntity
import io.github.inq_o.phonedoctor.core.database.AllowEntryDao
import io.github.inq_o.phonedoctor.core.database.AllowEntryEntity
import io.github.inq_o.phonedoctor.core.database.ScanRunDao
import io.github.inq_o.phonedoctor.core.datastore.UserPreferences
import io.github.inq_o.phonedoctor.core.model.AlertOutcome
import io.github.inq_o.phonedoctor.core.model.AlertStatus
import io.github.inq_o.phonedoctor.core.model.DeviceRole
import io.github.inq_o.phonedoctor.core.model.FamilyDecision
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import io.github.inq_o.phonedoctor.core.network.ApiException
import io.github.inq_o.phonedoctor.core.network.PhoneDoctorNetworkDataSource
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlert
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertCreate
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertKind
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertSource
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertStatus
import io.github.inq_o.phonedoctor.core.network.model.NetworkAppInfo
import io.github.inq_o.phonedoctor.core.network.model.NetworkDecisionType
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceStatus
import io.github.inq_o.phonedoctor.core.network.model.NetworkInstallSource
import io.github.inq_o.phonedoctor.core.network.model.NetworkOutcomeCreate
import io.github.inq_o.phonedoctor.core.network.model.NetworkOutcomeType
import io.github.inq_o.phonedoctor.core.network.model.NetworkProtectionState
import io.github.inq_o.phonedoctor.core.network.model.NetworkSymptomEvidence
import io.github.inq_o.phonedoctor.core.packages.InstalledApps
import io.github.inq_o.phonedoctor.core.usage.UsageAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** 가족 서버로 보낼 일을 WorkManager에 맡긴다 (sync:work). 가족 연결이 꺼진 빌드는 아무것도 하지 않는다 */
fun interface FamilySyncScheduler {
    fun schedule()
}

/** 시니어 폰의 보호 상태. 보호자가 "보호가 멈췄는지" 알 수 있게 6시간마다 알린다 (ux-flows F6) */
data class ProtectionState(val notifications: Boolean, val usageAccess: Boolean, val notificationListener: Boolean)

fun interface ProtectionStatus {
    fun current(): ProtectionState
}

/** 연결이 바뀔 때 가족 동기화가 할 일 (FamilyRepository가 부른다) */
interface FamilyLinkEvents {
    /** 시니어가 새 보호자와 연결함: 열린 요청을 새 보호자에게도 알린다 */
    suspend fun onLinkConfirmed()

    /** 모든 데이터 지우기: 서버 요청의 사본과 당겨 오기·보고 기록을 비운다 */
    suspend fun onDataDeleted()
}

/** 보호자 응답을 기기 사본에 반영한 결과. [notify]면 시니어에게 알린다 (열린 요청에 처음 온 응답) */
data class AppliedDecision(val alert: SymptomAlert, val decision: FamilyDecision, val guardianName: String?, val notify: Boolean)

/** [complete]가 false면 망 문제로 못 보낸 것이 있다 (다시 시도). [decisions]는 이번에 새로 안 보호자 응답 */
data class FamilySyncResult(val complete: Boolean, val decisions: List<AppliedDecision>)

/**
 * 시니어 폰 ↔ 가족 서버 (US4·F1′·F6). 기기 안 확인 요청(alert_local)을 서버 요청으로 보내고, 보호자 응답을 받아 오고,
 * 처리 결과와 보호 상태를 알린다. 가족과 연결해 시니어로 등록한 폰에서만 동작한다.
 * 보내는 것은 확인이 필요한 앱 하나의 이름·판·설치 정보와 광고 횟수뿐이다 (다른 앱 목록·사용 기록은 보내지 않는다)
 */
interface FamilyAlertSync {
    suspend fun sync(): FamilySyncResult

    /** 보호자 응답 (푸시 DECISION_MADE 또는 당겨 오기). 이미 안 응답이면 null */
    suspend fun applyDecision(serverAlertId: String, decision: String, guardianName: String?, messageToSenior: String?): AppliedDecision?

    /** 앱 상세(S2)를 열 때 서버 요청을 다시 읽는다 (푸시에는 없는 보호자 한마디). 실패하면 예외 */
    suspend fun refresh(localId: Long)
}

@Singleton
internal class DefaultFamilyAlertSync @Inject constructor(
    @FamilyEnabled private val available: Boolean,
    private val network: PhoneDoctorNetworkDataSource,
    private val preferences: UserPreferences,
    private val alertDao: AlertLocalDao,
    private val allowDao: AllowEntryDao,
    private val scanRunDao: ScanRunDao,
    private val installedApps: InstalledApps,
    private val protection: ProtectionStatus,
    private val scheduler: FamilySyncScheduler,
) : FamilyAlertSync, FamilyLinkEvents {
    internal var now: () -> Long = System::currentTimeMillis

    /** 응답 반영은 푸시와 동기화가 동시에 할 수 있다. 같은 응답을 두 번 알리지 않게 하나씩 */
    private val decisionLock = Mutex()

    override suspend fun sync(): FamilySyncResult {
        if (!available) return FamilySyncResult(true, emptyList())
        val device = preferences.registeredDevice.first()
        if (device == null || device.role != DeviceRole.SENIOR.name) return FamilySyncResult(true, emptyList())

        var complete = true
        var deviceGone = false
        val decisions = mutableListOf<AppliedDecision>()
        suspend fun step(block: suspend () -> Unit) {
            if (deviceGone) return
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                complete = false
            } catch (e: ApiException) {
                if (e.status == 403 && e.slug == "device-not-registered") {
                    deviceGone = true
                } else if (e.status == 429 || e.status >= 500) {
                    complete = false
                } else {
                    Log.w(TAG, "가족 동기화 거절: ${e.message}")
                }
            }
        }

        step { decisions += pull() }
        for (row in alertDao.pendingSync()) step { push(row, decisions) }
        step { reportStatusIfDue(device.deviceId) }

        if (deviceGone) {
            // 서버가 기기를 지웠다 (오래 안 씀 등). 연결도 요청도 서버에 없으니 사본을 비우고, 다음 연결 때 새로 등록한다
            preferences.setRegisteredDevice(null)
            preferences.setAlertPullCursor(null)
            alertDao.forgetServer()
        }
        return FamilySyncResult(complete, decisions)
    }

    override suspend fun applyDecision(serverAlertId: String, decision: String, guardianName: String?, messageToSenior: String?): AppliedDecision? =
        decisionLock.withLock {
            val row = alertDao.findByServerId(serverAlertId) ?: return@withLock null
            val type = FamilyDecision.entries.firstOrNull { it.wire == decision } ?: return@withLock null
            val known = row.decision
            if (known != null) {
                // 이미 안 응답. 한마디만 늦게 알게 됐을 수 있다
                if (messageToSenior != null) alertDao.setDecision(row.id, row.serverStatus ?: SERVER_DECIDED, known, row.guardianName ?: guardianName, messageToSenior)
                return@withLock null
            }
            alertDao.setDecision(row.id, SERVER_DECIDED, type.wire, guardianName, messageToSenior)
            val wasOpen = row.status == AlertStatus.OPEN.name
            if (type == FamilyDecision.ALLOW) {
                allowDao.insert(AllowEntryEntity(row.packageName, row.signingCertSha256, row.versionCode, guardianName.orEmpty(), now()))
                if (wasOpen) {
                    // 시니어 화면의 요청을 닫고, 서버에 "허용함" 결과를 보낼 것으로 표시
                    val fresh = alertDao.get(row.id) ?: return@withLock null
                    alertDao.update(fresh.copy(status = AlertStatus.RESOLVED.name, outcome = AlertOutcome.ALLOWED.name, updatedAt = now(), needsSync = true))
                }
            }
            val updated = alertDao.get(row.id)?.toModel() ?: return@withLock null
            AppliedDecision(updated, type, guardianName, notify = wasOpen)
        }

    override suspend fun onLinkConfirmed() {
        alertDao.markOpenForSync()
        scheduler.schedule()
    }

    override suspend fun onDataDeleted() {
        alertDao.forgetServer()
        preferences.setAlertPullCursor(null)
        preferences.setLastStatusReportAt(0)
    }

    override suspend fun refresh(localId: Long) {
        val row = alertDao.get(localId) ?: return
        val id = row.serverAlertId ?: return
        absorb(network.getAlert(id))
    }

    /** 내 요청 중 바뀐 것을 받아 온다 (DECISION_MADE 푸시를 놓쳤을 때 대비, push-messages.md §1) */
    private suspend fun pull(): List<AppliedDecision> {
        val applied = mutableListOf<AppliedDecision>()
        val since = preferences.alertPullCursor.first()
        var newest = since
        var page: String? = null
        do {
            val list = network.listAlerts(status = null, updatedSince = since, pageToken = page, pageSize = PAGE_SIZE)
            for (alert in list.items) {
                absorb(alert)?.let(applied::add)
                val changed = alert.updatedAt ?: alert.createdAt
                if (newest == null || changed.toInstant() > newest.toInstant()) newest = changed
            }
            page = list.nextPageToken
        } while (page != null)
        // 같은 시각도 다시 받으므로(updatedSince 포함) 마지막 값을 그대로 둔다
        preferences.setAlertPullCursor(newest)
        return applied
    }

    /** 서버 요청 하나를 사본에 반영한다 */
    private suspend fun absorb(alert: NetworkAlert): AppliedDecision? {
        val row = alertDao.findByServerId(alert.alertId) ?: return null
        val decision = alert.decision?.takeIf { it.decision != NetworkDecisionType.UNKNOWN }
        val applied = decision?.let { applyDecision(alert.alertId, it.decision.wire(), it.decidedBy.displayName, it.messageToSenior) }
        alert.status.wire()?.let { alertDao.setServerStatus(row.id, it) }
        return applied
    }

    private suspend fun push(row: AlertLocalEntity, decisions: MutableList<AppliedDecision>) {
        val evidenceChanged = row.sentDate != row.date || row.sentCount != row.count
        // 서버 요청이 답 없이 7일 만료됐는데 이 폰에서는 또 광고를 띄움: 서버에 새 요청으로 연다
        val serverWaiting = row.serverStatus == SERVER_OPEN || row.serverStatus == SERVER_EXPIRED
        val wantsEvidence = (row.kind == KIND_NOTICE || row.status == AlertStatus.OPEN.name) &&
            (row.serverAlertId == null || (serverWaiting && evidenceChanged))
        if (wantsEvidence) {
            try {
                val alert = network.createAlert(newKey(), alertBody(row))
                alertDao.markSent(row.id, alert.alertId, row.date, row.count, alert.status.wire() ?: SERVER_OPEN)
                absorb(alert)?.let(decisions::add)
            } catch (e: ApiException) {
                // 받을 보호자가 없음(연결 전·모두 끊김). 새 근거가 생기거나 새로 연결되면 다시 보낸다
                if (!(e.status == 409 && (e.slug == "no-active-link" || e.slug == "no-recipient"))) throw e
            }
        }
        val fresh = alertDao.get(row.id) ?: return
        if (fresh.status == AlertStatus.RESOLVED.name && fresh.serverAlertId != null && !fresh.outcomeSent && fresh.kind == KIND_APPROVAL) {
            sendOutcome(fresh)
        }
        alertDao.clearNeedsSync(row.id, row.updatedAt)
    }

    /** 처리 결과 (F1 15~19). 보내기 전에 서버 요청을 다시 읽어 지금 상태에 맞는 결과만 보낸다 */
    private suspend fun sendOutcome(row: AlertLocalEntity) {
        val id = row.serverAlertId ?: return
        val server = try {
            network.getAlert(id)
        } catch (e: ApiException) {
            // 연결을 끊어 서버가 요청을 지웠거나 볼 수 없음
            if (e.status == 404) alertDao.markOutcomeSent(row.id) else throw e
            return
        }
        absorb(server)
        val outcome = row.outcome?.let { runCatching { AlertOutcome.valueOf(it) }.getOrNull() }
        val decision = server.decision?.decision?.takeIf { it != NetworkDecisionType.UNKNOWN }?.let { d -> FamilyDecision.entries.first { it.wire == d.wire() } }
        val send = outcome?.let { outcomeToSend(it, server.status.wire(), decision) }
        if (send == null) {
            alertDao.markOutcomeSent(row.id)
            return
        }
        try {
            network.reportOutcome(newKey(), id, NetworkOutcomeCreate(send, Instant.ofEpochMilli(row.updatedAt).toString()))
            alertDao.setServerStatus(row.id, SERVER_RESOLVED)
        } catch (e: ApiException) {
            // 응답과 안 맞거나 이미 끝난 요청: 다시 보내도 같다
            if (e.status != 409 && e.status != 404) throw e
        }
        alertDao.markOutcomeSent(row.id)
    }

    private suspend fun reportStatusIfDue(deviceId: String) {
        val at = now()
        if (at - preferences.lastStatusReportAt.first() < STATUS_INTERVAL_MS) return
        // 검사가 한 번도 돌지 않았으면 알릴 것이 없다
        val checked = scanRunDao.latestStartedAt() ?: return
        val p = protection.current()
        network.reportStatus(
            deviceId,
            NetworkDeviceStatus(Instant.ofEpochMilli(checked).toString(), NetworkProtectionState(p.notifications, p.usageAccess, p.notificationListener)),
        )
        preferences.setLastStatusReportAt(at)
    }

    private fun alertBody(row: AlertLocalEntity): NetworkAlertCreate {
        val details = installedApps.details(row.packageName)
        return NetworkAlertCreate(
            source = NetworkAlertSource.SYMPTOM,
            kind = if (row.kind == KIND_NOTICE) NetworkAlertKind.NOTICE else NetworkAlertKind.APPROVAL,
            app = NetworkAppInfo(
                packageName = row.packageName,
                appLabel = row.label.take(APP_LABEL_MAX),
                versionCode = row.versionCode,
                versionName = details?.versionName,
                signingCertSha256 = row.signingCertSha256,
                installSource = installSource(details?.installerPackage),
                installerPackage = details?.installerPackage,
                // 앱이 그사이 지워졌으면 처음 본 근거 시각으로 대신한다 (필수 값)
                firstInstallTime = Instant.ofEpochMilli(details?.firstInstallTime ?: row.firstAt).toString(),
                lastUpdateTime = details?.lastUpdateTime?.let { Instant.ofEpochMilli(it).toString() },
            ),
            symptom = NetworkSymptomEvidence(
                date = row.date,
                count = row.count,
                days = row.days,
                firstAt = Instant.ofEpochMilli(row.firstAt).toString(),
                lastAt = Instant.ofEpochMilli(row.lastAt).toString(),
                detectorVersion = row.detectorVersion,
            ),
            detectedAt = Instant.ofEpochMilli(row.createdAt).toString(),
        )
    }

    // 같은 앱의 열린 요청은 서버가 하나로 합치고 결과 재전송도 같은 값이면 받아들이므로, 응답을 못 받았을 때 새 키로 다시 보내도 된다
    private fun newKey() = UUID.randomUUID().toString()

    private companion object {
        const val TAG = "PhoneDoctorFamilySync"
        const val PAGE_SIZE = 50
        const val APP_LABEL_MAX = 60
        const val STATUS_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}

/**
 * 기기의 처리 결과를 서버에 보낼 결과로 바꾼다 (openapi reportOutcome). 서버 상태에 맞지 않으면 null(보내지 않음).
 * 응답 전(open)에는 already_removed만, 응답 뒤에는 응답과 맞는 결과만 받는다
 */
internal fun outcomeToSend(local: AlertOutcome, serverStatus: String?, decision: FamilyDecision?): NetworkOutcomeType? {
    if (serverStatus == SERVER_RESOLVED || serverStatus == SERVER_EXPIRED) return null
    val removed = local == AlertOutcome.UNINSTALLED || local == AlertOutcome.ALREADY_REMOVED || local == AlertOutcome.VERSION_CHANGED
    return when (decision) {
        // 보호자 답 전에 지움 (시니어가 [지금 지울래요]를 누름 포함). 기록한 판은 이 폰에서 없어졌다
        null -> if (removed) NetworkOutcomeType.ALREADY_REMOVED else null
        FamilyDecision.RECOMMEND_UNINSTALL -> when (local) {
            AlertOutcome.UNINSTALLED -> NetworkOutcomeType.UNINSTALLED
            AlertOutcome.DECLINED -> NetworkOutcomeType.DECLINED
            AlertOutcome.ALREADY_REMOVED, AlertOutcome.VERSION_CHANGED -> NetworkOutcomeType.ALREADY_REMOVED
            AlertOutcome.FAILED, AlertOutcome.ALLOWED -> null
        }
        FamilyDecision.ALLOW -> when {
            local == AlertOutcome.ALLOWED -> NetworkOutcomeType.ALLOWED
            removed -> NetworkOutcomeType.ALREADY_REMOVED
            else -> null
        }
    }
}

/** 설치한 앱의 패키지 이름 → openapi InstallSource */
internal fun installSource(installer: String?): NetworkInstallSource = when (installer) {
    null -> NetworkInstallSource.UNKNOWN
    "com.android.vending" -> NetworkInstallSource.GOOGLE_PLAY
    "com.skt.skaf.A000Z00040", "com.kt.olleh.storefront", "com.lguplus.appstore", "com.onestore.app" -> NetworkInstallSource.ONE_STORE
    "com.sec.android.app.samsungapps" -> NetworkInstallSource.GALAXY_STORE
    "com.android.shell" -> NetworkInstallSource.ADB
    // 패키지 설치 관리자 = 브라우저·파일에서 받은 APK를 직접 설치
    "com.android.packageinstaller", "com.google.android.packageinstaller", "com.samsung.android.packageinstaller" ->
        NetworkInstallSource.BROWSER_OR_FILE
    else -> NetworkInstallSource.OTHER_STORE
}

private fun NetworkDecisionType.wire(): String = when (this) {
    NetworkDecisionType.RECOMMEND_UNINSTALL -> FamilyDecision.RECOMMEND_UNINSTALL.wire
    NetworkDecisionType.ALLOW -> FamilyDecision.ALLOW.wire
    NetworkDecisionType.UNKNOWN -> "_unknown"
}

private fun NetworkAlertStatus.wire(): String? = when (this) {
    NetworkAlertStatus.OPEN -> SERVER_OPEN
    NetworkAlertStatus.DECIDED -> SERVER_DECIDED
    NetworkAlertStatus.RESOLVED -> SERVER_RESOLVED
    NetworkAlertStatus.EXPIRED -> SERVER_EXPIRED
    NetworkAlertStatus.UNKNOWN -> null
}

internal class AndroidProtectionStatus @Inject constructor(
    @ApplicationContext private val context: Context,
    private val usageAccess: UsageAccess,
) : ProtectionStatus {
    override fun current(): ProtectionState {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return ProtectionState(
            notifications = permitted && NotificationManagerCompat.from(context).areNotificationsEnabled(),
            usageAccess = usageAccess.isGranted(),
            notificationListener = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context),
        )
    }
}
