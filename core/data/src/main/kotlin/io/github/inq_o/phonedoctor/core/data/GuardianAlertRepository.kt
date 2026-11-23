package io.github.inq_o.phonedoctor.core.data

import io.github.inq_o.phonedoctor.core.datastore.UserPreferences
import io.github.inq_o.phonedoctor.core.model.DeviceRole
import io.github.inq_o.phonedoctor.core.model.FamilyAlert
import io.github.inq_o.phonedoctor.core.model.FamilyAlertStatus
import io.github.inq_o.phonedoctor.core.model.FamilyAnswer
import io.github.inq_o.phonedoctor.core.model.FamilyDecision
import io.github.inq_o.phonedoctor.core.model.FamilyOutcome
import io.github.inq_o.phonedoctor.core.model.FamilySymptom
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
    /** 볼 수 있는 요청 전부(지금 연결로 받은 것, 90일 안), 최근에 바뀐 것부터. 등록 전이면 빈 목록 */
    suspend fun alerts(): List<FamilyAlert>

    /** 요청 하나. 볼 수 없으면(연결을 끊음·지워짐) ApiException 404 */
    suspend fun alert(alertId: String): FamilyAlert

    /**
     * 응답 (F1 11~12). 다른 보호자가 먼저 답했으면 [AlreadyAnsweredException], 응답 없이 끝난 요청이면 [AlertClosedException].
     * [messageToSenior]는 40자까지, 시니어 화면에 그대로 보인다
     */
    suspend fun decide(alertId: String, decision: FamilyDecision, messageToSenior: String?): FamilyAlert
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
) : GuardianAlertRepository {
    private val json = Json { ignoreUnknownKeys = true }

    /** 응답을 못 받은 응답 보내기의 Idempotency-Key. 같은 응답을 다시 보내면 같은 키로 */
    private val pendingKeys = mutableMapOf<String, String>()
    private val keysLock = Mutex()

    override suspend fun alerts(): List<FamilyAlert> {
        if (!available || !registeredGuardian()) return emptyList()
        val items = mutableListOf<NetworkAlert>()
        var page: String? = null
        var pages = 0
        do {
            val list = guarded { network.listAlerts(pageToken = page, pageSize = PAGE_SIZE) }
            items += list.items
            page = list.nextPageToken
        } while (page != null && ++pages < MAX_PAGES)
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
            guarded { network.decideAlert(key, alertId, body) }.toModel().also { keysLock.withLock { pendingKeys.remove(op) } }
        } catch (e: IOException) {
            throw e // 서버가 처리했는지 모름 → 다시 누르면 같은 키
        } catch (e: ApiException) {
            keysLock.withLock { pendingKeys.remove(op) }
            when {
                e.status == 409 && e.slug == "already-decided" -> throw AlreadyAnsweredException(existingAnswer(e))
                e.status == 409 && e.slug == "alert-closed" -> throw AlertClosedException()
                else -> throw e
            }
        }
    }

    private fun existingAnswer(e: ApiException): FamilyAnswer? = try {
        e.body?.get("existingDecision")?.let { json.decodeFromJsonElement(NetworkDecision.serializer(), it) }?.toAnswer()
    } catch (_: Exception) {
        null
    }

    private suspend fun registeredGuardian() = preferences.registeredDevice.first()?.role == DeviceRole.GUARDIAN.name

    /** 서버가 이 기기를 지웠으면 등록 없음으로 알린다 (FamilyRepository가 다음 연결 때 새로 등록) */
    private suspend fun <T> guarded(block: suspend () -> T): T = try {
        block()
    } catch (e: ApiException) {
        if (e.status == 403 && e.slug == "device-not-registered") throw DeviceNotRegisteredException()
        throw e
    }

    private companion object {
        const val PAGE_SIZE = 50
        const val MAX_PAGES = 10
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
