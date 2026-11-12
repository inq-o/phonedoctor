package io.github.inq_o.phonedoctor.feature.seniorappdetail.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.SymptomRepository
import io.github.inq_o.phonedoctor.core.model.AlertOutcome
import io.github.inq_o.phonedoctor.core.model.AlertStatus
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import io.github.inq_o.phonedoctor.core.notifications.Notifier
import io.github.inq_o.phonedoctor.core.packages.AppUninstaller
import io.github.inq_o.phonedoctor.core.packages.UninstallResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 삭제 시도의 진행 상태. 시스템이 성공을 알려도 앱이 실제로 없는지 다시 조회한 뒤에만 [REMOVED]로 본다 */
enum class UninstallPhase {
    IDLE,

    /** 시스템 삭제 확인창을 요청함 */
    REQUESTED,

    /** 확인창이 닫혔는데 앱이 그대로 있음 (시니어가 취소했거나 광고가 창을 닫음) */
    STILL_INSTALLED,

    FAILED,
    REMOVED,
}

sealed interface AppDetailUiState {
    data object Loading : AppDetailUiState

    /** 요청이 없거나 이미 닫힘 (오래된 알림을 눌렀을 때 등) */
    data object Gone : AppDetailUiState

    data class Ready(val alert: SymptomAlert, val phase: UninstallPhase) : AppDetailUiState
}

@HiltViewModel(assistedFactory = AppDetailViewModel.Factory::class)
class AppDetailViewModel @AssistedInject constructor(
    @Assisted private val alertId: Long,
    private val repository: SymptomRepository,
    private val uninstaller: AppUninstaller,
    private val notifier: Notifier,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(alertId: Long): AppDetailViewModel
    }

    private val phase = MutableStateFlow(UninstallPhase.IDLE)

    val uiState: StateFlow<AppDetailUiState> = combine(repository.alert(alertId), phase) { alert, phase ->
        when {
            // Room의 첫 값이 곧 조회 결과다. 읽기 전 상태는 stateIn 초기값(Loading)이 맡는다
            alert == null -> AppDetailUiState.Gone
            // 이 화면에서 지운 직후에는 "지웠어요"를 보여 준다
            phase == UninstallPhase.REMOVED -> AppDetailUiState.Ready(alert, phase)
            alert.status != AlertStatus.OPEN -> AppDetailUiState.Gone
            else -> AppDetailUiState.Ready(alert, phase)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppDetailUiState.Loading)

    init {
        viewModelScope.launch {
            uninstaller.results.collect { result ->
                val alert = (uiState.value as? AppDetailUiState.Ready)?.alert ?: return@collect
                if (result.packageName != alert.app.packageName) return@collect
                when (result) {
                    is UninstallResult.Success -> confirm(requested = true)
                    is UninstallResult.Cancelled -> confirm(requested = true)
                    is UninstallResult.Failed -> {
                        repository.recordOutcome(alertId, AlertOutcome.FAILED)
                        if (!confirm(requested = true)) phase.value = UninstallPhase.FAILED
                    }
                }
            }
        }
    }

    fun uninstall() {
        val alert = (uiState.value as? AppDetailUiState.Ready)?.alert ?: return
        phase.value = UninstallPhase.REQUESTED
        try {
            uninstaller.requestUninstall(alert.app.packageName)
        } catch (e: RuntimeException) {
            phase.value = UninstallPhase.FAILED
        }
    }

    /**
     * 화면이 다시 보일 때: 삭제를 요청했으면 결과가 오지 않았더라도 설치 여부를 다시 조회한다.
     * 요청한 적이 없는데 앱이 없으면 다른 경로로 이미 지워진 것이다.
     */
    fun onResume() {
        viewModelScope.launch { confirm(requested = phase.value != UninstallPhase.IDLE) }
    }

    /** 앱이 없어졌으면 true */
    private suspend fun confirm(requested: Boolean): Boolean {
        val removed = repository.confirmRemoved(alertId, requested)
        when {
            removed == true -> {
                notifier.cancelAlert(alertId)
                if (requested) phase.value = UninstallPhase.REMOVED
            }
            // 조회 실패(null)는 삭제 성공으로 치지 않는다
            requested && phase.value == UninstallPhase.REQUESTED -> phase.value = UninstallPhase.STILL_INSTALLED
        }
        return removed == true
    }
}
