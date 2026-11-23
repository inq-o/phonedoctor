package io.github.inq_o.phonedoctor.feature.seniorfamily.impl

import io.github.inq_o.phonedoctor.core.data.FamilyRepository
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
import kotlinx.coroutines.flow.flowOf
import java.time.Instant

internal fun link(id: String, status: LinkStatus, name: String = "민지", createdAt: Long = 0) =
    FamilyLink(id, status, "엄마", name, Instant.ofEpochSecond(createdAt), null, null)

internal fun apiError(status: Int, slug: String? = null) = ApiException(status, slug, null, null, null, null)

internal class FakeFamily : FamilyRepository {
    override val available = true
    override val role: Flow<DeviceRole?> = flowOf(DeviceRole.SENIOR)

    var links = mutableListOf<FamilyLink>()
    var codes = 0
    var codeExpiresAt: Instant = Instant.ofEpochSecond(600)

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

    override suspend fun createPairingCode(): PairingCode {
        step("code")
        codes++
        val code = "%06d".format(codes)
        return PairingCode(code, "phonedoctor://pair?c=$code", codeExpiresAt)
    }

    override val isGuardianSignedIn = false
    override suspend fun signInGuardian(googleIdToken: String) = error("부르지 않음")
    override suspend fun acceptPairingCode(code: String): FamilyLink = error("시니어 화면에서 부르지 않음")

    override suspend fun links(): List<FamilyLink> {
        step("links")
        return links.toList()
    }

    override suspend fun confirmLink(linkId: String): FamilyLink {
        step("confirm:$linkId")
        val i = links.indexOfFirst { it.linkId == linkId }
        links[i] = links[i].copy(status = LinkStatus.ACTIVE)
        return links[i]
    }

    override suspend fun revokeLink(linkId: String) {
        step("revoke:$linkId")
        val i = links.indexOfFirst { it.linkId == linkId }
        if (i >= 0) links[i] = links[i].copy(status = LinkStatus.REVOKED)
    }

    override suspend fun updatePushToken(token: String) = Unit
    override suspend fun deleteAllData() = Unit
    override suspend fun reportAppVersion() = Unit
    override val displayName: Flow<String?> = flowOf(null)
    override suspend fun setDisplayName(name: String) = Unit
    override val notifyCaution: Flow<Boolean> = flowOf(false)
    override suspend fun setNotifyCaution(enabled: Boolean) = Unit
    override suspend fun reauthenticateGuardian(googleIdToken: String) = Unit
}

internal class FakeNotifier : Notifier {
    val cancelledLinks = mutableListOf<String>()
    override fun canNotify() = true
    override fun notifyAlert(alert: SymptomAlert) = Unit
    override fun cancelAlert(alertId: Long) = Unit
    override fun notifyDecision(alert: SymptomAlert, decision: FamilyDecision, guardianName: String?) = Unit
    override fun notifyLinkRequested(linkId: String, guardianName: String?) = Unit
    override fun notifyLinkRevoked(linkId: String, guardianName: String?, seniorName: String?) = Unit
    override fun notifyLinkActivated(linkId: String, seniorName: String?) = Unit
    override fun cancelLink(linkId: String) {
        cancelledLinks += linkId
    }
    override fun notifyContactLost(linkId: String, seniorName: String?) = Unit
    override fun notifyGuardianAlert(message: GuardianAlertMessage) = Unit
    override fun notifyAlertResolved(alertId: String, outcome: String?, seniorName: String?, appLabel: String?) = Unit
    override fun cancelGuardianAlert(alertId: String) = Unit
}
