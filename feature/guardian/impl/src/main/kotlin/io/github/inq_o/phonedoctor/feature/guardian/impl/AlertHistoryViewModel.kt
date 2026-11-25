package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.data.GuardianAlertRepository
import io.github.inq_o.phonedoctor.core.model.FamilyAlert
import io.github.inq_o.phonedoctor.core.model.LinkStatus
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

    /** [phoneCount]: 이 보호자가 연결한 폰 수. 2대 이상이면 줄마다 폰 이름을 붙인다 (요청의 linkIds에는 다른 보호자의 연결도 있다) */
    data class Ready(val alerts: List<FamilyAlert>, val phoneCount: Int = 1) : AlertHistoryUiState
}

/** G3 요청 기록. 최근에 바뀐 것부터 */
@HiltViewModel
class AlertHistoryViewModel @Inject constructor(
    private val repository: GuardianAlertRepository,
    private val family: FamilyRepository,
) : ViewModel() {
    private val state = MutableStateFlow<AlertHistoryUiState>(AlertHistoryUiState.Loading)
    val uiState: StateFlow<AlertHistoryUiState> = state.asStateFlow()

    private var loading: Job? = null

    fun refresh() {
        if (loading?.isActive == true) return
        if (state.value is AlertHistoryUiState.Failed) state.value = AlertHistoryUiState.Loading
        loading = viewModelScope.launch {
            state.value = try {
                val alerts = repository.alerts()
                // 연결 목록을 못 읽으면 폰 이름을 붙여 둔다 (헷갈리지 않는 쪽)
                val phones = runCatchingNonCancel { family.links() }?.count { it.status == LinkStatus.ACTIVE } ?: 2
                AlertHistoryUiState.Ready(alerts, phones)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 보이던 목록이 있으면 그대로 둔다
                state.value as? AlertHistoryUiState.Ready ?: AlertHistoryUiState.Failed(e.toGuardianError())
            }
        }
    }
}
