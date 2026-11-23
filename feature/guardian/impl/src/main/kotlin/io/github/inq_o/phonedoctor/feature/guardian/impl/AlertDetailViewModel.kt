package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.AlertClosedException
import io.github.inq_o.phonedoctor.core.data.AlreadyAnsweredException
import io.github.inq_o.phonedoctor.core.data.GuardianAlertRepository
import io.github.inq_o.phonedoctor.core.data.MESSAGE_TO_SENIOR_MAX
import io.github.inq_o.phonedoctor.core.model.FamilyAlert
import io.github.inq_o.phonedoctor.core.model.FamilyAnswer
import io.github.inq_o.phonedoctor.core.model.FamilyDecision
import io.github.inq_o.phonedoctor.core.network.ApiException
import io.github.inq_o.phonedoctor.core.notifications.Notifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface AlertDetailUiState {
    data object Loading : AlertDetailUiState

    data class Failed(val error: GuardianError) : AlertDetailUiState

    /** 볼 수 없는 요청 (연결을 끊었거나 90일이 지나 지워짐) */
    data object NotFound : AlertDetailUiState

    data class Ready(
        val alert: FamilyAlert,
        /** 엄마께 한마디 (선택, 40자) */
        val message: String = "",
        val sending: FamilyDecision? = null,
        val error: GuardianError? = null,
        /** 답하려 했는데 다른 보호자가 먼저 답함 (FR-024) */
        val answeredFirst: FamilyAnswer? = null,
        /** 방금 이 화면에서 답함 */
        val answered: Boolean = false,
    ) : AlertDetailUiState
}

/** G2. 답하면 시니어 폰에 DECISION_MADE가 간다 */
@HiltViewModel(assistedFactory = AlertDetailViewModel.Factory::class)
class AlertDetailViewModel @AssistedInject constructor(
    @Assisted private val alertId: String,
    private val repository: GuardianAlertRepository,
    private val notifier: Notifier,
) : ViewModel() {
    private val state = MutableStateFlow<AlertDetailUiState>(AlertDetailUiState.Loading)
    val uiState: StateFlow<AlertDetailUiState> = state.asStateFlow()

    init {
        // 화면으로 봤으니 알림은 지운다
        notifier.cancelGuardianAlert(alertId)
        load()
    }

    /** 처음과 [다시 불러오기]. 보이던 내용은 불러오는 동안 그대로 둔다 */
    fun load() {
        viewModelScope.launch {
            if (state.value is AlertDetailUiState.Failed) state.value = AlertDetailUiState.Loading
            try {
                val alert = repository.alert(alertId)
                state.update { (it as? AlertDetailUiState.Ready)?.copy(alert = alert) ?: AlertDetailUiState.Ready(alert) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                state.value = if (e.status == 404) AlertDetailUiState.NotFound else failedOrKeep(e.toGuardianError())
            } catch (e: Exception) {
                state.value = failedOrKeep(e.toGuardianError())
            }
        }
    }

    fun onMessageChange(text: String) {
        state.update { (it as? AlertDetailUiState.Ready)?.copy(message = text.take(MESSAGE_TO_SENIOR_MAX)) ?: it }
    }

    fun decide(decision: FamilyDecision) {
        val ready = state.value as? AlertDetailUiState.Ready ?: return
        if (ready.sending != null || !ready.alert.answerable) return
        // 알림 전용(이미 허용한 앱)에는 [괜찮은 앱이에요]가 없다 (서버도 400)
        if (ready.alert.notice && decision == FamilyDecision.ALLOW) return
        state.value = ready.copy(sending = decision, error = null)
        viewModelScope.launch {
            val next = try {
                val alert = repository.decide(alertId, decision, ready.message.takeIf { decision == FamilyDecision.RECOMMEND_UNINSTALL })
                ready.copy(alert = alert, sending = null, answered = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: AlreadyAnsweredException) {
                ready.copy(sending = null, answeredFirst = e.answer).also { reloadQuietly() }
            } catch (e: AlertClosedException) {
                ready.copy(sending = null).also { reloadQuietly() }
            } catch (e: ApiException) {
                if (e.status == 404) {
                    state.value = AlertDetailUiState.NotFound
                    return@launch
                }
                ready.copy(sending = null, error = e.toGuardianError())
            } catch (e: Exception) {
                ready.copy(sending = null, error = e.toGuardianError())
            }
            state.value = next
        }
    }

    /** 답이 거절됐을 때 지금 상태를 다시 읽는다 (먼저 온 답·결과를 보이게) */
    private fun reloadQuietly() {
        viewModelScope.launch {
            val alert = runCatchingNonCancel { repository.alert(alertId) } ?: return@launch
            state.update { (it as? AlertDetailUiState.Ready)?.copy(alert = alert) ?: it }
        }
    }

    private fun failedOrKeep(error: GuardianError): AlertDetailUiState =
        (state.value as? AlertDetailUiState.Ready)?.copy(error = error) ?: AlertDetailUiState.Failed(error)

    @AssistedFactory
    interface Factory {
        fun create(alertId: String): AlertDetailViewModel
    }
}
