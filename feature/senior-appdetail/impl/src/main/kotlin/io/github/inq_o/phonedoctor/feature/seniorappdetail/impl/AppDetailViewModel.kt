package io.github.inq_o.phonedoctor.feature.seniorappdetail.impl

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.FamilyAlertSync
import io.github.inq_o.phonedoctor.core.data.SymptomRepository
import io.github.inq_o.phonedoctor.core.model.AlertOutcome
import io.github.inq_o.phonedoctor.core.model.AlertStatus
import io.github.inq_o.phonedoctor.core.model.FamilyDecision
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import io.github.inq_o.phonedoctor.core.notifications.Notifier
import io.github.inq_o.phonedoctor.core.packages.AppUninstaller
import io.github.inq_o.phonedoctor.core.packages.UninstallResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    data class Ready(val alert: SymptomAlert, val phase: UninstallPhase) : AppDetailUiState {
        /** 가족에게 알렸고 답을 기다리는 중. 시니어는 기다리지 않고 지울 수도 있다 (ux-flows S2 "시니어 본인 결정권") */
        val waitingForFamily: Boolean get() = alert.family?.waiting == true && phase == UninstallPhase.IDLE

        /** 보호자가 지우자고 함 */
        val familyRecommends: Boolean get() = alert.family?.decision == FamilyDecision.RECOMMEND_UNINSTALL
    }

    /** 보호자가 [괜찮은 앱이에요]로 허용함 (F1 끝 주석) */
    data class Allowed(val guardianName: String?, val message: String? = null) : AppDetailUiState
}

@HiltViewModel(assistedFactory = AppDetailViewModel.Factory::class)
class AppDetailViewModel @AssistedInject constructor(
    @Assisted private val alertId: Long,
    private val saved: SavedStateHandle,
    private val repository: SymptomRepository,
    private val uninstaller: AppUninstaller,
    private val notifier: Notifier,
    private val familySync: FamilyAlertSync,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(alertId: Long): AppDetailViewModel
    }

    private companion object {
        const val KEY_PHASE = "uninstallPhase"
    }

    /** 삭제 창을 띄운 상태는 저장해 둔다: 창이 떠 있는 동안 앱 프로세스가 끝나도 돌아왔을 때 결과를 판단할 수 있게 */
    private val phase = MutableStateFlow(saved.get<String>(KEY_PHASE)?.let(UninstallPhase::valueOf) ?: UninstallPhase.IDLE)

    private fun setPhase(p: UninstallPhase) {
        phase.value = p
        saved[KEY_PHASE] = p.name
    }

    private val declineLock = Mutex()

    val uiState: StateFlow<AppDetailUiState> = combine(repository.alert(alertId), phase) { alert, phase ->
        when {
            // Room의 첫 값이 곧 조회 결과다. 읽기 전 상태는 stateIn 초기값(Loading)이 맡는다
            alert == null -> AppDetailUiState.Gone
            // 이 화면에서 지운 직후에는 "지웠어요"를 보여 준다
            phase == UninstallPhase.REMOVED -> AppDetailUiState.Ready(alert, phase)
            alert.outcome == AlertOutcome.ALLOWED -> AppDetailUiState.Allowed(alert.family?.guardianName, alert.family?.messageToSenior)
            // 닫혔어도 보여 주는 요청 (다시 지울 수 있게): 지우자고 한 앱을 취소한 요청(하루 뒤 알림을 눌러 옴), 강력 모드로 멈춘 앱
            alert.status != AlertStatus.OPEN &&
                !(alert.outcome == AlertOutcome.DECLINED && alert.family?.decision == FamilyDecision.RECOMMEND_UNINSTALL) &&
                alert.outcome != AlertOutcome.SUSPENDED -> AppDetailUiState.Gone
            else -> AppDetailUiState.Ready(alert, phase)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppDetailUiState.Loading)

    init {
        // 가족에게 보낸 요청이면 서버에서 다시 읽는다: 보호자 한마디는 푸시에 없고, 푸시를 놓쳤을 수도 있다. 실패하면 지금 아는 대로 보여 준다
        viewModelScope.launch {
            try {
                familySync.refresh(alertId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 다음 동기화 때
            }
        }
        viewModelScope.launch {
            uninstaller.results.collect { result ->
                val alert = (uiState.value as? AppDetailUiState.Ready)?.alert ?: return@collect
                if (result.packageName != alert.app.packageName) return@collect
                when (result) {
                    is UninstallResult.Success -> confirm(requested = true)
                    is UninstallResult.Cancelled -> if (!confirm(requested = true)) declineIfRecommended()
                    is UninstallResult.Failed -> {
                        repository.recordOutcome(alertId, AlertOutcome.FAILED)
                        if (!confirm(requested = true)) setPhase(UninstallPhase.FAILED)
                    }
                }
            }
        }
    }

    fun uninstall() {
        val alert = (uiState.value as? AppDetailUiState.Ready)?.alert ?: return
        setPhase(UninstallPhase.REQUESTED)
        try {
            uninstaller.requestUninstall(alert.app.packageName)
        } catch (e: RuntimeException) {
            setPhase(UninstallPhase.FAILED)
        }
    }

    /**
     * 화면이 다시 보일 때: 삭제를 요청했으면 결과가 오지 않았더라도 설치 여부를 다시 조회한다.
     * 요청한 적이 없는데 앱이 없으면 다른 경로로 이미 지워진 것이다.
     */
    fun onResume() {
        viewModelScope.launch {
            val requested = phase.value != UninstallPhase.IDLE
            // 삭제 창에서 돌아왔는데 아직 있으면 취소한 것 (결과 방송을 놓쳤어도: 창이 떠 있는 동안 프로세스가 끝난 경우)
            if (!confirm(requested) && phase.value == UninstallPhase.STILL_INSTALLED) declineIfRecommended()
        }
    }

    /**
     * 보호자가 지우자고 한 앱을 시니어가 시스템 창에서 취소함: declined로 닫는다 (보호자에게 "아직 안 지웠어요", 하루 뒤 한 번 다시 알림).
     * 보호자 답 전이면 요청은 그대로 둔다 (가족 답을 기다리는 중, 서버도 답 전에는 declined를 받지 않음)
     */
    private suspend fun declineIfRecommended() = declineLock.withLock {
        // 화면 상태가 아니라 지금 저장된 요청으로 본다 (취소 직전에 보호자 답이 막 도착했을 수 있다). 결과 방송과 화면 복귀가 겹쳐도 한 번만
        val latest = repository.alert(alertId).first() ?: return@withLock
        if (latest.status != AlertStatus.OPEN || latest.family?.decision != FamilyDecision.RECOMMEND_UNINSTALL) return@withLock
        repository.recordOutcome(alertId, AlertOutcome.DECLINED)
    }

    /** 앱이 없어졌으면 true */
    private suspend fun confirm(requested: Boolean): Boolean {
        val removed = repository.confirmRemoved(alertId, requested)
        when {
            removed == true -> {
                notifier.cancelAlert(alertId)
                if (requested) setPhase(UninstallPhase.REMOVED)
            }
            // 조회 실패(null)는 삭제 성공으로 치지 않는다
            requested && phase.value == UninstallPhase.REQUESTED -> setPhase(UninstallPhase.STILL_INSTALLED)
        }
        return removed == true
    }
}
