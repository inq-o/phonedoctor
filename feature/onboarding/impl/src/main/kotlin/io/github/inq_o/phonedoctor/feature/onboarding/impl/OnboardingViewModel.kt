package io.github.inq_o.phonedoctor.feature.onboarding.impl

import android.content.Intent
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.FamilyRepository
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
import javax.inject.Qualifier

/** 사이트 광고 알림(US10)을 쓰는 빌드인지 (feature:senior-sites SiteAlertsFeature.ENABLED) */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SiteAlertsEnabled

enum class OnboardingStep {
    DISCLOSURE,
    NOTIFICATION,
    USAGE_ACCESS,

    /** (선택) 알림 접근: Chrome 사이트 광고 알림 찾기 (US10). 이 기능이 켜진 빌드에서만 */
    NOTIFICATION_ACCESS,

    /** O-S4 가족 연결 안내. 가족 연결을 쓸 수 있는 빌드에서만. 건너뛸 수 있다 */
    FAMILY,
}

data class OnboardingUiState(
    val steps: List<OnboardingStep>,
    val index: Int = 0,
    val usageAccessGranted: Boolean = false,
    val finished: Boolean = false,
    /** 끝낼 때 [가족 연결하기]를 눌렀으면 true. 홈 위에 연결 코드 화면을 연다 */
    val connectFamily: Boolean = false,
) {
    val step: OnboardingStep get() = steps[index]
}

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val usageAccess: UsageAccess,
    private val notifier: Notifier,
    private val settings: UserSettingsRepository,
    private val scheduler: ScanScheduler,
    family: FamilyRepository,
    @SiteAlertsEnabled siteAlerts: Boolean,
) : ViewModel() {
    private val state = MutableStateFlow(
        OnboardingUiState(
            steps = buildList {
                add(OnboardingStep.DISCLOSURE)
                // 알림 권한 요청 창은 Android 13부터 있다. 그 전 기기는 단계를 건너뛴다
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notifier.canNotify()) add(OnboardingStep.NOTIFICATION)
                add(OnboardingStep.USAGE_ACCESS)
                if (siteAlerts) add(OnboardingStep.NOTIFICATION_ACCESS)
                if (family.available) add(OnboardingStep.FAMILY)
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

    /**
     * 사용 기록 보기를 켜지 않아도 끝낼 수 있다. 홈에서 다시 안내한다 (US11 인수 조건 4).
     * [connectFamily]: 마지막 단계에서 [가족 연결하기]를 누름. 연결 코드는 홈으로 넘어간 뒤 그 화면이 받는다
     */
    fun finish(connectFamily: Boolean = false) {
        if (finishing) return
        finishing = true
        viewModelScope.launch {
            settings.completeOnboarding(DISCLOSURE_VERSION)
            scheduler.scanNow("onboarding")
            state.update { it.copy(finished = true, connectFamily = connectFamily) }
        }
    }

    private var finishing = false

    companion object {
        /** 고지 문구가 바뀌면 올린다 */
        const val DISCLOSURE_VERSION = 1
    }
}
