package io.github.inq_o.phonedoctor.feature.onboarding.impl

import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.ScanScheduler
import io.github.inq_o.phonedoctor.core.data.UserSettingsRepository
import io.github.inq_o.phonedoctor.core.notifications.Notifier
import io.github.inq_o.phonedoctor.core.usage.UsageAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OnboardingStep { DISCLOSURE, NOTIFICATION, USAGE_ACCESS }

data class OnboardingUiState(
    val steps: List<OnboardingStep>,
    val index: Int = 0,
    val usageAccessGranted: Boolean = false,
    val finished: Boolean = false,
) {
    val step: OnboardingStep get() = steps[index]
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val usageAccess: UsageAccess,
    private val notifier: Notifier,
    private val settings: UserSettingsRepository,
    private val scheduler: ScanScheduler,
) : ViewModel() {
    private val state = MutableStateFlow(
        OnboardingUiState(
            // 알림 권한 요청 창은 Android 13부터 있다. 그 전 기기는 단계를 건너뛴다
            steps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifier.canNotify()) {
                listOf(OnboardingStep.DISCLOSURE, OnboardingStep.NOTIFICATION, OnboardingStep.USAGE_ACCESS)
            } else {
                listOf(OnboardingStep.DISCLOSURE, OnboardingStep.USAGE_ACCESS)
            },
            usageAccessGranted = usageAccess.isGranted(),
        ),
    )
    val uiState: StateFlow<OnboardingUiState> = state.asStateFlow()

    val usageAccessSettingsIntents: List<Intent> get() = usageAccess.settingsIntents()

    /** 설정 화면에서 돌아왔을 때 권한 상태를 다시 읽는다 */
    fun refresh() {
        state.update { it.copy(usageAccessGranted = usageAccess.isGranted()) }
    }

    fun next() {
        val s = state.value
        if (s.index < s.steps.lastIndex) {
            state.update { it.copy(index = it.index + 1) }
        } else {
            finish()
        }
    }

    /** 사용 기록 보기를 켜지 않아도 끝낼 수 있다. 홈에서 다시 안내한다 (US11 인수 조건 4) */
    fun finish() {
        viewModelScope.launch {
            settings.completeOnboarding(DISCLOSURE_VERSION)
            scheduler.scanNow("onboarding")
            state.update { it.copy(finished = true) }
        }
    }

    companion object {
        /** 고지 문구가 바뀌면 올린다 */
        const val DISCLOSURE_VERSION = 1
    }
}
