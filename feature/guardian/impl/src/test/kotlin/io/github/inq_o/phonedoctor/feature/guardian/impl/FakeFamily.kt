package io.github.inq_o.phonedoctor.feature.guardian.impl

import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.model.DeviceRole
import io.github.inq_o.phonedoctor.core.model.FamilyLink
import io.github.inq_o.phonedoctor.core.model.LinkStatus
import io.github.inq_o.phonedoctor.core.model.PairingCode
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import io.github.inq_o.phonedoctor.core.network.ApiException
import io.github.inq_o.phonedoctor.core.notifications.Notifier
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
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
    override suspend fun deleteAllData() = Unit
}

internal class FakeNotifier : Notifier {
    val cancelled = mutableListOf<String>()
    override fun canNotify() = true
    override fun notifyAlert(alert: SymptomAlert) = Unit
    override fun cancelAlert(alertId: Long) = Unit
    override fun notifyLinkRequested(linkId: String, guardianName: String?) = Unit
    override fun notifyLinkActivated(linkId: String, seniorName: String?) = Unit
    override fun notifyLinkRevoked(linkId: String, guardianName: String?, seniorName: String?) = Unit
    override fun cancelLink(linkId: String) {
        cancelled += linkId
    }
}
