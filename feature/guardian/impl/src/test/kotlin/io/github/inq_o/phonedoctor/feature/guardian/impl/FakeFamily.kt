package io.github.inq_o.phonedoctor.feature.guardian.impl

import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.data.GuardianAlertRepository
import io.github.inq_o.phonedoctor.core.model.FamilyAlert
import io.github.inq_o.phonedoctor.core.model.FamilyAlertStatus
import io.github.inq_o.phonedoctor.core.model.FamilyAnswer
import io.github.inq_o.phonedoctor.core.model.FamilySymptom
import io.github.inq_o.phonedoctor.core.model.DeviceRole
import io.github.inq_o.phonedoctor.core.model.FamilyDecision
import io.github.inq_o.phonedoctor.core.model.FamilyLink
import io.github.inq_o.phonedoctor.core.model.LinkStatus
import io.github.inq_o.phonedoctor.core.model.PairingCode
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import io.github.inq_o.phonedoctor.core.network.ApiException
import io.github.inq_o.phonedoctor.core.notifications.GuardianAlertMessage
import io.github.inq_o.phonedoctor.core.notifications.Notifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import java.time.Instant

internal fun link(
    id: String,
    status: LinkStatus,
    senior: String = "엄마",
    createdAt: Long = 0,
    activatedAt: Instant? = null,
    lastCheckedAt: Instant? = null,
) = FamilyLink(id, status, senior, "민지", Instant.ofEpochSecond(createdAt), activatedAt, lastCheckedAt)

internal fun apiError(status: Int, slug: String? = null, retryAfter: Long? = null) = ApiException(status, slug, null, null, null, retryAfter)

internal class FakeFamily : FamilyRepository {
    override val available = true
    override val role: Flow<DeviceRole?> = flowOf(DeviceRole.GUARDIAN)
    override var isGuardianSignedIn = false

    var links = mutableListOf<FamilyLink>()
    val tokens = mutableListOf<String>()
    val codes = mutableListOf<String>()

    /** 다음 호출에서 던질 오류 (한 번만) */
    val fail = mutableMapOf<String, Exception>()

    /** 있으면 그 동작이 이 값이 끝날 때까지 기다린다 */
    val gate = mutableMapOf<String, CompletableDeferred<Unit>>()
    val calls = mutableListOf<String>()

    private suspend fun step(op: String) {
        calls += op
        gate[op]?.await()
        fail.remove(op)?.let { throw it }
    }

    override suspend fun signInGuardian(googleIdToken: String) {
        step("signIn")
        tokens += googleIdToken
        isGuardianSignedIn = true
    }

    override suspend fun acceptPairingCode(code: String): FamilyLink {
        step("accept")
        codes += code
        return link("new-$code", LinkStatus.PENDING, senior = "엄마").also { links += it }
    }

    override suspend fun links(): List<FamilyLink> {
        step("links")
        return links.toList()
    }

    override suspend fun revokeLink(linkId: String) {
        step("revoke")
        links.removeAll { it.linkId == linkId }
    }

    override suspend fun createPairingCode(): PairingCode = error("보호자 화면에서 부르지 않음")
    override suspend fun confirmLink(linkId: String): FamilyLink = error("보호자 화면에서 부르지 않음")
    override suspend fun updatePushToken(token: String) = Unit
    var deletes = 0
    override suspend fun deleteAllData() {
        step("delete")
        deletes++
    }
    override suspend fun reportAppVersion() = Unit
    override suspend fun resumePendingDeletion() = false
    override val displayName = MutableStateFlow<String?>(null)
    override suspend fun setDisplayName(name: String) {
        step("name")
        displayName.value = name
    }
    override val notifyCaution = MutableStateFlow(false)
    override suspend fun setNotifyCaution(enabled: Boolean) {
        step("caution")
        notifyCaution.value = enabled
    }
    val reauthTokens = mutableListOf<String>()
    override suspend fun reauthenticateGuardian(googleIdToken: String) {
        step("reauth")
        reauthTokens += googleIdToken
    }
}

internal class FakeNotifier : Notifier {
    val cancelled = mutableListOf<String>()
    override fun canNotify() = true
    override fun notifyAlert(alert: SymptomAlert) = Unit
    override fun cancelAlert(alertId: Long) = Unit
    override fun notifyDecision(alert: SymptomAlert, decision: FamilyDecision, guardianName: String?) = Unit
    override fun notifyLinkRequested(linkId: String, guardianName: String?) = Unit
    override fun notifyLinkActivated(linkId: String, seniorName: String?) = Unit
    override fun notifyLinkRevoked(linkId: String, guardianName: String?, seniorName: String?) = Unit
    override fun cancelLink(linkId: String) {
        cancelled += linkId
    }
    override fun notifyContactLost(linkId: String, seniorName: String?) = Unit
    override fun notifyGuardianAlert(message: GuardianAlertMessage) = Unit
    override fun notifyAlertResolved(alertId: String, outcome: String?, seniorName: String?, appLabel: String?) = Unit
    override fun cancelGuardianAlert(alertId: String) {
        cancelled += "request:$alertId"
    }
    override fun notifySiteAds(alert: io.github.inq_o.phonedoctor.core.model.SiteAlert, stillOn: Boolean) = Unit
    override fun cancelSite(site: String) = Unit
}

internal fun request(
    id: String,
    status: FamilyAlertStatus = FamilyAlertStatus.OPEN,
    notice: Boolean = false,
    answer: FamilyAnswer? = null,
    updated: Long = 0,
) = FamilyAlert(
    alertId = id, seniorName = "엄마", status = status, notice = notice, fromInstallScan = false, appLabel = "초고속 클리너",
    packageName = "com.ad", versionName = "1.0", installSource = "BROWSER_OR_FILE", firstInstallTime = null,
    symptom = FamilySymptom("2026-11-23", 12, 1, Instant.EPOCH, Instant.EPOCH, "usage-test"),
    answer = answer, outcome = null, createdAt = Instant.EPOCH, updatedAt = Instant.ofEpochSecond(updated),
)

internal class FakeAlerts : GuardianAlertRepository {
    val alerts = mutableListOf<FamilyAlert>()
    val fail = mutableMapOf<String, Exception>()
    val decided = mutableListOf<Triple<String, FamilyDecision, String?>>()
    val gate = mutableMapOf<String, CompletableDeferred<Unit>>()
    override val falsePositiveFeedback = MutableStateFlow(true)
    override suspend fun setFalsePositiveFeedback(enabled: Boolean) {
        falsePositiveFeedback.value = enabled
    }

    private suspend fun step(op: String) {
        gate.remove(op)?.await()
        fail.remove(op)?.let { throw it }
    }

    val statusAsked = mutableListOf<FamilyAlertStatus?>()
    override suspend fun alerts(status: FamilyAlertStatus?): List<FamilyAlert> {
        step("alerts")
        statusAsked += status
        return alerts.filter { status == null || it.status == status }
    }

    override suspend fun alert(alertId: String): FamilyAlert {
        step("alert")
        return alerts.firstOrNull { it.alertId == alertId } ?: throw apiError(404)
    }

    override suspend fun decide(alertId: String, decision: FamilyDecision, messageToSenior: String?): FamilyAlert {
        step("decide")
        decided += Triple(alertId, decision, messageToSenior)
        val answered = alert(alertId).copy(
            status = FamilyAlertStatus.DECIDED,
            answer = FamilyAnswer(decision, "민지", messageToSenior, Instant.EPOCH),
        )
        alerts.replaceAll { if (it.alertId == alertId) answered else it }
        return answered
    }
}
