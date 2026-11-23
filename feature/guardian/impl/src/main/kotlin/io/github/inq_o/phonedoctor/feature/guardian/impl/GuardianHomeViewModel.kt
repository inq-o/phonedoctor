package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.DeviceNotRegisteredException
import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.model.FamilyLink
import io.github.inq_o.phonedoctor.core.model.LinkStatus
import io.github.inq_o.phonedoctor.core.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/** 보호자 홈의 연결 하나. [contactLost]: 시니어 폰의 검사 보고가 48시간 넘게 없음 (ux-flows F6) */
data class GuardianLink(val link: FamilyLink, val contactLost: Boolean = false)

/** 연결 끊기·요청 취소 결과 안내 */
sealed interface GuardianMessage {
    data class Revoked(val seniorName: String, val wasPending: Boolean) : GuardianMessage
    data class Failed(val error: GuardianError) : GuardianMessage
}

sealed interface GuardianHomeUiState {
    data object Loading : GuardianHomeUiState

    data class Failed(val error: GuardianError) : GuardianHomeUiState

    /** [links]: 연결됨 먼저, 그다음 답을 기다리는 요청. [revoking]은 끊는 중인 연결 */
    data class Ready(
        val links: List<GuardianLink>,
        val revoking: String? = null,
        val message: GuardianMessage? = null,
    ) : GuardianHomeUiState
}

/** G1 보호자 홈. 화면이 보일 때마다 연결 목록을 다시 불러온다 (놓친 LINK_* 푸시 대비, push-messages.md §1) */
@HiltViewModel
class GuardianHomeViewModel @Inject constructor(private val family: FamilyRepository) : ViewModel() {
    private val state = MutableStateFlow<GuardianHomeUiState>(GuardianHomeUiState.Loading)
    val uiState: StateFlow<GuardianHomeUiState> = state.asStateFlow()

    internal var now: () -> Instant = Instant::now

    private var loading: Job? = null

    fun refresh() {
        if (loading?.isActive == true) return
        if (state.value is GuardianHomeUiState.Failed) state.value = GuardianHomeUiState.Loading
        loading = viewModelScope.launch {
            val links = try {
                family.links()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 보이던 목록이 있으면 그대로 둔다 (다음에 다시)
                if (state.value !is GuardianHomeUiState.Ready) state.value = GuardianHomeUiState.Failed(e.toGuardianError())
                return@launch
            }
            val current = state.value as? GuardianHomeUiState.Ready
            state.value = GuardianHomeUiState.Ready(visible(links), current?.revoking)
        }
    }

    fun revoke(link: FamilyLink) {
        val ready = state.value as? GuardianHomeUiState.Ready ?: return
        if (ready.revoking != null) return
        state.value = ready.copy(revoking = link.linkId, message = null)
        viewModelScope.launch {
            var deviceGone = false
            val error = try {
                family.revokeLink(link.linkId)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeviceNotRegisteredException) {
                // 서버가 이 기기를 지웠으면 연결도 모두 없다
                deviceGone = true
                null
            } catch (e: ApiException) {
                // 이미 없는 연결이면 끊은 것과 같다
                if (e.status == 404) null else e.toGuardianError()
            } catch (e: Exception) {
                e.toGuardianError()
            }
            val now = state.value as? GuardianHomeUiState.Ready ?: return@launch
            state.value = if (error == null) {
                now.copy(
                    links = if (deviceGone) emptyList() else now.links.filterNot { it.link.linkId == link.linkId },
                    revoking = null,
                    message = GuardianMessage.Revoked(link.seniorName, wasPending = link.status == LinkStatus.PENDING),
                )
            } else {
                now.copy(revoking = null, message = GuardianMessage.Failed(error))
            }
        }
    }

    private fun visible(links: List<FamilyLink>): List<GuardianLink> {
        val at = now()
        return links
            .filter { it.status == LinkStatus.ACTIVE || it.status == LinkStatus.PENDING }
            .sortedWith(compareBy<FamilyLink> { it.status != LinkStatus.ACTIVE }.thenBy { it.createdAt })
            .map { GuardianLink(it, contactLost = it.isContactLost(at)) }
    }
}

/** 서버의 CONTACT_LOST 판정과 같은 기준: 마지막 검사 보고(없으면 연결 확정 시각)에서 48시간 */
internal val CONTACT_LOST_AFTER: Duration = Duration.ofHours(48)

internal fun FamilyLink.isContactLost(now: Instant): Boolean {
    if (status != LinkStatus.ACTIVE) return false
    val last = lastCheckedAt ?: activatedAt ?: return false
    return Duration.between(last, now) >= CONTACT_LOST_AFTER
}
