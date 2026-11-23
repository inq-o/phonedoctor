package io.github.inq_o.phonedoctor.feature.seniorfamily.impl

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
import javax.inject.Inject

/** 연결 끊기 결과 안내 */
sealed interface RevokeMessage {
    data class Revoked(val name: String) : RevokeMessage
    data class Failed(val error: FamilyError) : RevokeMessage
}

sealed interface FamilyLinksUiState {
    data object Loading : FamilyLinksUiState

    data class Failed(val error: FamilyError) : FamilyLinksUiState

    /** [links]는 연결됨·확인 대기만, 오래된 것부터. [revoking]은 끊는 중인 연결 */
    data class Ready(
        val links: List<FamilyLink>,
        val revoking: String? = null,
        val message: RevokeMessage? = null,
    ) : FamilyLinksUiState {
        /** 보호자 최대 3명 (data-model §2). 서버처럼 연결된 것만 센다 (확인 대기는 시니어가 거절할 수 있음) */
        val canAdd: Boolean get() = links.count { it.status == LinkStatus.ACTIVE } < MAX_GUARDIANS
    }
}

const val MAX_GUARDIANS = 3

/** S5 가족 연결 관리 */
@HiltViewModel
class FamilyLinksViewModel @Inject constructor(private val family: FamilyRepository) : ViewModel() {
    private val state = MutableStateFlow<FamilyLinksUiState>(FamilyLinksUiState.Loading)
    val uiState: StateFlow<FamilyLinksUiState> = state.asStateFlow()

    private var loading: Job? = null

    /** 화면이 보일 때마다 (S3에서 돌아온 뒤 등). 이미 보이는 목록은 불러오는 동안 그대로 둔다 */
    fun refresh() {
        if (loading?.isActive == true) return
        if (state.value is FamilyLinksUiState.Failed) state.value = FamilyLinksUiState.Loading
        loading = viewModelScope.launch {
            val links = try {
                family.links()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 보이던 목록이 있으면 그대로 둔다 (다음에 다시)
                if (state.value !is FamilyLinksUiState.Ready) state.value = FamilyLinksUiState.Failed(e.toFamilyError())
                return@launch
            }
            // 끊기 결과 안내는 다시 보일 때 지운다 (화면 낭독이 또 읽지 않게)
            val current = state.value as? FamilyLinksUiState.Ready
            state.value = FamilyLinksUiState.Ready(visible(links), current?.revoking)
        }
    }

    fun revoke(link: FamilyLink) {
        val ready = state.value as? FamilyLinksUiState.Ready ?: return
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
                if (e.status == 404) null else e.toFamilyError()
            } catch (e: Exception) {
                e.toFamilyError()
            }
            val now = state.value as? FamilyLinksUiState.Ready ?: return@launch
            state.value = if (error == null) {
                now.copy(
                    links = if (deviceGone) emptyList() else now.links.filterNot { it.linkId == link.linkId },
                    revoking = null,
                    message = RevokeMessage.Revoked(link.guardianName),
                )
            } else {
                now.copy(revoking = null, message = RevokeMessage.Failed(error))
            }
        }
    }

    private fun visible(links: List<FamilyLink>) =
        links.filter { it.status == LinkStatus.ACTIVE || it.status == LinkStatus.PENDING }.sortedBy { it.createdAt }
}
