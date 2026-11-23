package io.github.inq_o.phonedoctor.core.data

import io.github.inq_o.phonedoctor.core.datastore.UserPreferences
import io.github.inq_o.phonedoctor.core.model.DeviceRole
import io.github.inq_o.phonedoctor.core.model.FamilyAlert
import io.github.inq_o.phonedoctor.core.model.FamilyAlertStatus
import io.github.inq_o.phonedoctor.core.model.FamilyAnswer
import io.github.inq_o.phonedoctor.core.model.FamilyDecision
import io.github.inq_o.phonedoctor.core.model.FamilyOutcome
import io.github.inq_o.phonedoctor.core.model.FamilySymptom
import io.github.inq_o.phonedoctor.core.model.FamilyVerdict
import io.github.inq_o.phonedoctor.core.network.ApiException
import io.github.inq_o.phonedoctor.core.network.PhoneDoctorNetworkDataSource
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlert
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertKind
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertSource
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertStatus
import io.github.inq_o.phonedoctor.core.network.model.NetworkDecision
import io.github.inq_o.phonedoctor.core.network.model.NetworkDecisionCreate
import io.github.inq_o.phonedoctor.core.network.model.NetworkDecisionType
import io.github.inq_o.phonedoctor.core.network.model.NetworkOutcomeType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** (보호자) 연결한 부모님 폰들의 요청 (US4: G1 목록, G2 상세·응답, G3 기록). 서버에서 바로 읽는다 */
interface GuardianAlertRepository {
    /**
     * 볼 수 있는 요청(지금 연결로 받은 것, 90일 안), 최근에 바뀐 것부터. [status]면 그 상태만 (G1은 열린 요청만).
     * 등록 전이면 빈 목록
     */
    suspend fun alerts(status: FamilyAlertStatus? = null): List<FamilyAlert>

    /** 요청 하나. 볼 수 없으면(연결을 끊음·지워짐) ApiException 404 */
    suspend fun alert(alertId: String): FamilyAlert

    /**
     * 응답 (F1 11~12). 다른 보호자가 먼저 답했으면 [AlreadyAnsweredException], 응답 없이 끝난 요청이면 [AlertClosedException].
     * [messageToSenior]는 40자까지, 시니어 화면에 그대로 보인다
     */
    suspend fun decide(alertId: String, decision: FamilyDecision, messageToSenior: String?): FamilyAlert

    /** [괜찮은 앱이에요]로 답하면 익명으로 오탐을 알릴지 (US7, 설정 G4에서 끌 수 있음) */
    val falsePositiveFeedback: Flow<Boolean>

    suspend fun setFalsePositiveFeedback(enabled: Boolean)
}

/** 오탐 익명 신고 한 건 (openapi FalsePositiveReport). 기기·사용자를 알 수 있는 값은 없다 */
data class FalsePositive(
    val installScan: Boolean,
    val packageName: String,
    val versionCode: Long,
    val signingCertSha256: String,
    val modelVersion: String?,
    val score: Double?,
    val reasonCodes: List<String>?,
    val detectorVersion: String?,
)

/** 오탐 신고를 망이 될 때까지 재시도하며 보낸다 (sync:work). 요청 하나에 한 번만 */
fun interface FalsePositiveReporter {
    fun schedule(alertId: String, report: FalsePositive)
}

/** 먼저 온 응답만 유효하다 (FR-024). [answer]는 먼저 답한 응답 */
class AlreadyAnsweredException(val answer: FamilyAnswer?) : IllegalStateException("다른 보호자가 먼저 답함")

/** 시니어가 먼저 지웠거나 7일이 지나 응답 없이 끝난 요청 */
class AlertClosedException : IllegalStateException("이미 끝난 요청")

/** 시니어 화면 한마디 최대 길이 (openapi DecisionCreate) */
const val MESSAGE_TO_SENIOR_MAX = 40

@Singleton
internal class DefaultGuardianAlertRepository @Inject constructor(
    @FamilyEnabled private val available: Boolean,
    private val network: PhoneDoctorNetworkDataSource,
    private val preferences: UserPreferences,
    private val falsePositives: FalsePositiveReporter,
) : GuardianAlertRepository {
    override val falsePositiveFeedback: Flow<Boolean> = preferences.falsePositiveFeedback

    override suspend fun setFalsePositiveFeedback(enabled: Boolean) = preferences.setFalsePositiveFeedback(enabled)

    private val json = Json { ignoreUnknownKeys = true }

    /** 응답을 못 받은 응답 보내기의 Idempotency-Key. 같은 응답을 다시 보내면 같은 키로 */
    private val pendingKeys = mutableMapOf<String, String>()
    private val keysLock = Mutex()

    override suspend fun alerts(status: FamilyAlertStatus?): List<FamilyAlert> {
        if (!available || registeredDevice()?.role != DeviceRole.GUARDIAN.name) return emptyList()
        val filter = when (status) {
            FamilyAlertStatus.OPEN -> NetworkAlertStatus.OPEN
            FamilyAlertStatus.DECIDED -> NetworkAlertStatus.DECIDED
            FamilyAlertStatus.RESOLVED -> NetworkAlertStatus.RESOLVED
            FamilyAlertStatus.EXPIRED -> NetworkAlertStatus.EXPIRED
            FamilyAlertStatus.UNKNOWN, null -> null
        }
        // 서버는 바뀐 순서 오름차순으로 준다: 끝까지 읽어야 최신 요청이 빠지지 않는다. 같은 쪽 표시가 되풀이되면 멈춘다
        val items = mutableListOf<NetworkAlert>()
        val seen = mutableSetOf<String>()
        var page: String? = null
        do {
            val list = guarded { network.listAlerts(status = filter, pageToken = page, pageSize = PAGE_SIZE) }
            items += list.items
            page = list.nextPageToken?.takeIf { seen.add(it) }
        } while (page != null)
        // 같은 요청이 페이지 경계에서 두 번 올 수 있다 (같은 시각 포함) → 마지막 값으로
        return items.associateBy { it.alertId }.values.map { it.toModel() }.sortedByDescending { it.updatedAt }
    }

    override suspend fun alert(alertId: String): FamilyAlert {
        check(available) { "이 빌드는 가족 연결이 꺼져 있음" }
        return guarded { network.getAlert(alertId) }.toModel()
    }

    override suspend fun decide(alertId: String, decision: FamilyDecision, messageToSenior: String?): FamilyAlert {
        check(available) { "이 빌드는 가족 연결이 꺼져 있음" }
        val message = messageToSenior?.trim()?.take(MESSAGE_TO_SENIOR_MAX)?.ifEmpty { null }
        val op = "decide:$alertId:${decision.wire}:$message"
        val key = keysLock.withLock { pendingKeys.getOrPut(op) { UUID.randomUUID().toString() } }
        val body = NetworkDecisionCreate(
            if (decision == FamilyDecision.ALLOW) NetworkDecisionType.ALLOW else NetworkDecisionType.RECOMMEND_UNINSTALL,
            message,
        )
        return try {
            val alert = guarded { network.decideAlert(key, alertId, body) }
            keysLock.withLock { pendingKeys.remove(op) }
            // 괜찮은 앱이라고 답함 = 판정이 틀렸을 수 있음. 켜 두었으면 앱·판·판정기만 익명으로 알린다 (요청마다 한 번)
            if (decision == FamilyDecision.ALLOW && preferences.falsePositiveFeedback.first()) {
                alert.toFalsePositive()?.let { falsePositives.schedule(alertId, it) }
            }
            alert.toModel()
        } catch (e: IOException) {
            throw e // 서버가 처리했는지 모름 → 다시 누르면 같은 키
        } catch (e: ApiException) {
            keysLock.withLock { pendingKeys.remove(op) }
            when {
                e.status == 409 && e.slug == "already-decided" -> {
                    val existing = existingDecision(e)
                    // 망 오류로 결과를 못 받은 내 응답이 이미 같은 답으로 들어가 있음 (서버도 같은 보호자·같은 답만 성공으로 본다).
                    // 다른 답이면 먼저 들어간 내 답을 보여 준다
                    if (existing != null && existing.decidedBy.deviceId == registeredDevice()?.deviceId && existing.decision == body.decision) {
                        return guarded { network.getAlert(alertId) }.toModel()
                    }
                    throw AlreadyAnsweredException(existing?.toAnswer())
                }
                e.status == 409 && e.slug == "alert-closed" -> throw AlertClosedException()
                else -> throw e
            }
        }
    }

    private fun existingDecision(e: ApiException): NetworkDecision? = try {
        e.body?.get("existingDecision")?.let { json.decodeFromJsonElement(NetworkDecision.serializer(), it) }
    } catch (_: Exception) {
        null
    }

    private suspend fun registeredDevice() = preferences.registeredDevice.first()

    /** 서버가 이 기기를 지웠으면 등록 없음으로 알린다 (FamilyRepository가 다음 연결 때 새로 등록) */
    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: ApiException) {
        if (e.status == 403 && e.slug == "device-not-registered") throw DeviceNotRegisteredException()
        throw e
    }

    private companion object {
        const val PAGE_SIZE = 50
    }
}

/** 판정 근거가 있을 때만 (계약: install_scan은 모델 판·점수·근거, symptom은 감지 규칙 판 필수) */
internal fun NetworkAlert.toFalsePositive(): FalsePositive? {
    val v = verdict
    return when {
        source == NetworkAlertSource.INSTALL_SCAN && v != null -> FalsePositive(
            true, app.packageName, app.versionCode, app.signingCertSha256, v.modelVersion, v.score, v.reasons.map { it.code }, null,
        )
        symptom != null -> FalsePositive(
            false, app.packageName, app.versionCode, app.signingCertSha256, null, null, null, symptom!!.detectorVersion,
        )
        else -> null
    }
}

internal fun NetworkDecision.toAnswer(): FamilyAnswer? {
    val type = when (decision) {
        NetworkDecisionType.RECOMMEND_UNINSTALL -> FamilyDecision.RECOMMEND_UNINSTALL
        NetworkDecisionType.ALLOW -> FamilyDecision.ALLOW
        NetworkDecisionType.UNKNOWN -> return null
    }
    return FamilyAnswer(type, decidedBy.displayName, messageToSenior, decidedAt.toInstant())
}

internal fun NetworkAlert.toModel() = FamilyAlert(
    alertId = alertId,
    seniorName = protectedDevice?.displayName.orEmpty(),
    status = when (status) {
        NetworkAlertStatus.OPEN -> FamilyAlertStatus.OPEN
        NetworkAlertStatus.DECIDED -> FamilyAlertStatus.DECIDED
        NetworkAlertStatus.RESOLVED -> FamilyAlertStatus.RESOLVED
        NetworkAlertStatus.EXPIRED -> FamilyAlertStatus.EXPIRED
        NetworkAlertStatus.UNKNOWN -> FamilyAlertStatus.UNKNOWN
    },
    notice = kind == NetworkAlertKind.NOTICE,
    fromInstallScan = source == NetworkAlertSource.INSTALL_SCAN,
    appLabel = app.appLabel,
    packageName = app.packageName,
    versionName = app.versionName,
    installSource = app.installSource.name.takeIf { it != "UNKNOWN" },
    firstInstallTime = runCatching { app.firstInstallTime.toInstant() }.getOrNull(),
    symptom = symptom?.let { FamilySymptom(it.date, it.count, it.days, it.firstAt.toInstant(), it.lastAt.toInstant(), it.detectorVersion) },
    verdict = verdict?.let { v -> FamilyVerdict(v.level.name, v.score, v.modelVersion, v.reasons.map { it.code }) },
    answer = decision?.toAnswer(),
    outcome = outcome?.let {
        when (it.outcome) {
            NetworkOutcomeType.UNINSTALLED -> FamilyOutcome.UNINSTALLED
            NetworkOutcomeType.DECLINED -> FamilyOutcome.DECLINED
            NetworkOutcomeType.ALREADY_REMOVED -> FamilyOutcome.ALREADY_REMOVED
            NetworkOutcomeType.SUSPENDED -> FamilyOutcome.SUSPENDED
            NetworkOutcomeType.ALLOWED -> FamilyOutcome.ALLOWED
            NetworkOutcomeType.UNKNOWN -> FamilyOutcome.UNKNOWN
        }
    },
    createdAt = createdAt.toInstant(),
    updatedAt = (updatedAt ?: createdAt).toInstant(),
)
