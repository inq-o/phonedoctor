package io.github.inq_o.phonedoctor.feature.seniorfamily.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.DeviceNotRegisteredException
import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.model.FamilyLink
import io.github.inq_o.phonedoctor.core.model.LinkStatus
import io.github.inq_o.phonedoctor.core.network.ApiException
import io.github.inq_o.phonedoctor.core.notifications.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface LinkConfirmUiState {
    data object Loading : LinkConfirmUiState

    /** 연결 요청을 불러오지 못함 (망 등). 다시 시도할 수 있다 */
    data class LoadFailed(val error: FamilyError) : LinkConfirmUiState

    /**
     * 답을 기다리는 요청. [busy]면 보내는 중, [error]는 앞선 시도의 실패.
     * [limitReached]: 연결하려 했더니 보호자가 이미 3명. 요청은 서버에 남아 있으므로 거절은 할 수 있게 둔다
     */
    data class Ready(
        val link: FamilyLink,
        val busy: Boolean = false,
        val error: FamilyError? = null,
        val limitReached: Boolean = false,
    ) : LinkConfirmUiState

    data class Connected(val guardianName: String) : LinkConfirmUiState

    data class Declined(val guardianName: String) : LinkConfirmUiState

    /** 보호자가 취소했거나 이미 끊긴 요청 (서버가 이 기기를 지운 경우 포함) */
    data object Gone : LinkConfirmUiState
}

/** S3 연결 확인. 시니어 본인 동의 단계라 생략하지 않는다 (ux-flows F2) */
@HiltViewModel(assistedFactory = LinkConfirmViewModel.Factory::class)
class LinkConfirmViewModel @AssistedInject constructor(
    @Assisted private val linkId: String,
    private val family: FamilyRepository,
    private val notifier: Notifier,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(linkId: String): LinkConfirmViewModel
    }

    private val state = MutableStateFlow<LinkConfirmUiState>(LinkConfirmUiState.Loading)
    val uiState: StateFlow<LinkConfirmUiState> = state.asStateFlow()

    init {
        load()
    }

    /** 푸시의 이름은 오래됐을 수 있어 서버의 지금 상태로 보여 준다 */
    fun load() {
        state.value = LinkConfirmUiState.Loading
        viewModelScope.launch {
            val link = try {
                family.links().firstOrNull { it.linkId == linkId }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = LinkConfirmUiState.LoadFailed(e.toFamilyError())
                return@launch
            }
            state.value = when (link?.status) {
                LinkStatus.PENDING -> LinkConfirmUiState.Ready(link)
                LinkStatus.ACTIVE -> LinkConfirmUiState.Connected(link.guardianName)
                else -> LinkConfirmUiState.Gone
            }
            if (link?.status != LinkStatus.PENDING) notifier.cancelLink(linkId)
        }
    }

    fun confirm() = answer { ready ->
        try {
            family.confirmLink(ready.link.linkId)
            LinkConfirmUiState.Connected(ready.link.guardianName)
        } catch (e: ApiException) {
            when {
                // 알림은 남겨 둔다: 다른 가족을 끊은 뒤 다시 답할 수 있다
                e.slug == "guardian-limit" -> return@answer ready.copy(busy = false, limitReached = true)
                e.status == 404 || e.slug == "link-revoked" -> LinkConfirmUiState.Gone
                else -> throw e
            }
        } catch (e: DeviceNotRegisteredException) {
            LinkConfirmUiState.Gone
        }
    }

    /** 거절은 연결 끊기와 같다 (openapi confirmLink: 거절은 DELETE) */
    fun decline() = answer { ready ->
        try {
            family.revokeLink(ready.link.linkId)
        } catch (e: ApiException) {
            // 이미 없는 요청이면 거절한 것과 같다
            if (e.status != 404) throw e
        } catch (e: DeviceNotRegisteredException) {
            // 서버가 이 기기를 지웠으면 연결 요청도 이미 없다
        }
        LinkConfirmUiState.Declined(ready.link.guardianName)
    }

    /** [send]가 돌려준 화면으로. 답이 끝난 화면(Ready가 아님)이면 알림도 지운다 */
    private fun answer(send: suspend (LinkConfirmUiState.Ready) -> LinkConfirmUiState) {
        val ready = state.value as? LinkConfirmUiState.Ready ?: return
        if (ready.busy) return
        state.value = ready.copy(busy = true, error = null)
        viewModelScope.launch {
            state.value = try {
                send(ready).also { if (it !is LinkConfirmUiState.Ready) notifier.cancelLink(linkId) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ready.copy(busy = false, error = e.toFamilyError())
            }
        }
    }
}
