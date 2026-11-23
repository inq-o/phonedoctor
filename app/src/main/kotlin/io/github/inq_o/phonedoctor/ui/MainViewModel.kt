package io.github.inq_o.phonedoctor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.data.ScanScheduler
import io.github.inq_o.phonedoctor.core.data.UserSettingsRepository
import io.github.inq_o.phonedoctor.core.data.isGuardianPhone
import io.github.inq_o.phonedoctor.core.model.DeviceRole
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 알림이 여는 화면 */
sealed interface PendingOpen {
    /** S2 앱 상세 */
    data class Alert(val alertId: Long) : PendingOpen

    /** S3 연결 확인 */
    data class LinkRequest(val linkId: String) : PendingOpen

    /** 연결 목록 (시니어 S5, 보호자 G1) */
    data object FamilyLinks : PendingOpen
}

/** 앱을 열 때 처음 보일 화면 */
enum class StartScreen {
    /** O1. 가족 연결을 쓸 수 있는 빌드의 첫 실행 */
    ROLE_CHOICE,
    SENIOR_ONBOARDING,
    SENIOR_HOME,

    /** 보호자를 골랐고 아직 서버에 등록하기 전. 역할 선택으로 돌아갈 수 있다 */
    GUARDIAN_SIGN_IN,

    /** 보호자로 등록했는데 로그인이 풀림. 역할은 모든 데이터 지우기 전에는 바꿀 수 없어 역할 선택으로 돌아가지 않는다 */
    GUARDIAN_SIGN_IN_AGAIN,
    GUARDIAN_HOME,
}

/**
 * 역할 선택이 없는 빌드(출시: 가족 연결 꺼짐)와 역할 선택이 생기기 전에 설정을 끝낸 기기는 시니어.
 * 보호자를 골랐어도 가족 연결이 꺼진 빌드면 시니어 첫 설정부터 (보호자 화면은 서버 없이 쓸 수 없다).
 * 서버에 등록한 역할이 있으면 고른 역할보다 우선한다 ([isGuardianPhone])
 */
internal fun startScreen(
    onboardingDone: Boolean,
    chosenRole: DeviceRole?,
    registeredRole: DeviceRole?,
    familyAvailable: Boolean,
    guardianSignedIn: Boolean,
): StartScreen = when {
    isGuardianPhone(chosenRole, registeredRole, familyAvailable) -> when {
        guardianSignedIn -> StartScreen.GUARDIAN_HOME
        registeredRole == DeviceRole.GUARDIAN -> StartScreen.GUARDIAN_SIGN_IN_AGAIN
        else -> StartScreen.GUARDIAN_SIGN_IN
    }
    onboardingDone -> StartScreen.SENIOR_HOME
    chosenRole == null && registeredRole == null && familyAvailable -> StartScreen.ROLE_CHOICE
    else -> StartScreen.SENIOR_ONBOARDING
}

@HiltViewModel
class MainViewModel @Inject constructor(
    settings: UserSettingsRepository,
    private val scheduler: ScanScheduler,
    private val family: FamilyRepository,
) : ViewModel() {
    /** 설정을 읽기 전에는 null */
    val start: StateFlow<StartScreen?> = combine(settings.onboardingDone, settings.chosenRole, family.role) { done, chosen, registered ->
        startScreen(done, chosen, registered, family.available, family.isGuardianSignedIn)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** 가족 연결을 쓸 수 있는 빌드인지 (역할 선택으로 돌아갈 수 있는지) */
    val familyAvailable: Boolean get() = family.available

    /** O1에서 보호자를 골랐을 때 이미 로그인돼 있으면 로그인 화면을 건너뛴다 */
    val guardianSignedIn: Boolean get() = family.isGuardianSignedIn

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
     * 앱을 열 때마다 한 번 살펴본다 (주기 작업이 늦어져도 화면이 최신이게). 시니어 첫 설정 전(보호자 폰 포함)에는 하지 않는다.
     * 프로세스가 막 떴을 때는 설정을 아직 못 읽었으므로 값이 정해질 때까지 기다린다
     */
    fun onAppOpened() {
        viewModelScope.launch {
            if (start.filterNotNull().first() == StartScreen.SENIOR_HOME) scheduler.scanNow("app_open")
        }
        // 앱이 업데이트됐으면 서버의 기기 정보를 고친다 (등록한 폰만, 실패하면 다음에 열 때)
        viewModelScope.launch {
            try {
                family.reportAppVersion()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 다음에
            }
        }
    }
}
