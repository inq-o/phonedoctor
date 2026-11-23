package io.github.inq_o.phonedoctor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.data.ScanScheduler
import io.github.inq_o.phonedoctor.core.data.UserSettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 알림이 여는 화면 */
sealed interface PendingOpen {
    /** S2 앱 상세 */
    data class Alert(val alertId: Long) : PendingOpen

    /** S3 연결 확인 */
    data class LinkRequest(val linkId: String) : PendingOpen

    /** S5 가족 연결 관리 */
    data object FamilyLinks : PendingOpen
}

@HiltViewModel
class MainViewModel @Inject constructor(
    settings: UserSettingsRepository,
    private val scheduler: ScanScheduler,
    private val family: FamilyRepository,
) : ViewModel() {
    /** 설정을 읽기 전에는 null */
    val onboardingDone: StateFlow<Boolean?> = settings.onboardingDone
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val pending = MutableStateFlow<PendingOpen?>(null)

    /** 알림을 눌러 들어왔을 때 열 화면. 연 뒤 [consumePendingOpen] */
    val pendingOpen: StateFlow<PendingOpen?> = pending.asStateFlow()

    fun open(target: PendingOpen) {
        // 가족 연결을 쓸 수 없는 빌드(출시)는 다른 앱이 보낸 인텐트로도 연결 화면을 열지 않는다
        if (target !is PendingOpen.Alert && !family.available) return
        pending.value = target
    }

    fun consumePendingOpen() {
        pending.value = null
    }

    /**
     * 앱을 열 때마다 한 번 살펴본다 (주기 작업이 늦어져도 화면이 최신이게). 첫 설정 전에는 하지 않는다.
     * 프로세스가 막 떴을 때는 설정을 아직 못 읽었으므로 값이 정해질 때까지 기다린다
     */
    fun onAppOpened() {
        viewModelScope.launch {
            if (onboardingDone.filterNotNull().first()) scheduler.scanNow("app_open")
        }
    }
}
