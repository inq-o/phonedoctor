package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.data.PhoneNameRepository
import io.github.inq_o.phonedoctor.core.model.LinkStatus
import io.github.inq_o.phonedoctor.core.notifications.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface LinkWaitingUiState {
    val seniorName: String

    /** 시니어가 [연결할게요]를 누르기를 기다림 */
    data class Waiting(override val seniorName: String) : LinkWaitingUiState

    data class Connected(override val seniorName: String) : LinkWaitingUiState

    /** 시니어가 [안 할래요]를 눌렀거나 연결이 없어짐 */
    data class Declined(override val seniorName: String) : LinkWaitingUiState
}

/**
 * O-G3. 화면이 보이는 동안 5초마다 연결 상태를 확인한다 (LINK_ACTIVATED 푸시가 늦거나 빠져도 넘어가게).
 * 남은 시간은 보여 주지 않는다: 연결 요청에는 기한이 없다
 */
@HiltViewModel(assistedFactory = LinkWaitingViewModel.Factory::class)
class LinkWaitingViewModel @AssistedInject constructor(
    @Assisted("linkId") private val linkId: String,
    @Assisted("seniorName") seniorName: String,
    private val family: FamilyRepository,
    private val notifier: Notifier,
    private val phoneNames: PhoneNameRepository,
) : ViewModel() {
    private val state = MutableStateFlow<LinkWaitingUiState>(LinkWaitingUiState.Waiting(seniorName))
    val uiState: StateFlow<LinkWaitingUiState> = state.asStateFlow()

    private var watching: Job? = null

    fun startWatching() {
        if (watching?.isActive == true || state.value !is LinkWaitingUiState.Waiting) return
        watching = viewModelScope.launch {
            while (isActive && state.value is LinkWaitingUiState.Waiting) {
                check()
                if (state.value !is LinkWaitingUiState.Waiting) break
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** O-G4: 연결된 뒤 고른 표시 이름을 이 폰에 저장 (null이면 건너뜀). 저장한 뒤 [onDone] */
    fun saveName(name: String?, onDone: () -> Unit) {
        viewModelScope.launch {
            if (name != null) phoneNames.setChosenName(linkId, name)
            onDone()
        }
    }

    fun stopWatching() {
        watching?.cancel()
        watching = null
    }

    private suspend fun check() {
        val links = try {
            family.links()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return // 망이 잠깐 끊겨도 기다리는 화면은 그대로. 다음 확인에서 다시
        }
        val link = links.firstOrNull { it.linkId == linkId }
        val name = link?.seniorName ?: state.value.seniorName
        state.value = when (link?.status) {
            LinkStatus.PENDING, LinkStatus.UNKNOWN -> LinkWaitingUiState.Waiting(name)
            LinkStatus.ACTIVE -> {
                // 화면으로 이미 보고 있으니 "연결됐어요" 알림은 지운다
                notifier.cancelLink(linkId)
                LinkWaitingUiState.Connected(name)
            }
            LinkStatus.REVOKED, null -> {
                // 거절도 LINK_REVOKED 알림으로 온다. 화면으로 보고 있으니 지운다
                notifier.cancelLink(linkId)
                LinkWaitingUiState.Declined(name)
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(@Assisted("linkId") linkId: String, @Assisted("seniorName") seniorName: String): LinkWaitingViewModel
    }

    private companion object {
        const val POLL_INTERVAL_MS = 5_000L
    }
}
