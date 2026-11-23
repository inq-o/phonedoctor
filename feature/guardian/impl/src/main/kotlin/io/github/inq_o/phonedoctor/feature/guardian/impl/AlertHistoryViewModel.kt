package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.GuardianAlertRepository
import io.github.inq_o.phonedoctor.core.model.FamilyAlert
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface AlertHistoryUiState {
    data object Loading : AlertHistoryUiState

    data class Failed(val error: GuardianError) : AlertHistoryUiState

    data class Ready(val alerts: List<FamilyAlert>) : AlertHistoryUiState
}

/** G3 요청 기록. 최근에 바뀐 것부터 */
@HiltViewModel
class AlertHistoryViewModel @Inject constructor(private val repository: GuardianAlertRepository) : ViewModel() {
    private val state = MutableStateFlow<AlertHistoryUiState>(AlertHistoryUiState.Loading)
    val uiState: StateFlow<AlertHistoryUiState> = state.asStateFlow()

    private var loading: Job? = null

    fun refresh() {
        if (loading?.isActive == true) return
        if (state.value is AlertHistoryUiState.Failed) state.value = AlertHistoryUiState.Loading
        loading = viewModelScope.launch {
            state.value = try {
                AlertHistoryUiState.Ready(repository.alerts())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 보이던 목록이 있으면 그대로 둔다
                state.value as? AlertHistoryUiState.Ready ?: AlertHistoryUiState.Failed(e.toGuardianError())
            }
        }
    }
}
