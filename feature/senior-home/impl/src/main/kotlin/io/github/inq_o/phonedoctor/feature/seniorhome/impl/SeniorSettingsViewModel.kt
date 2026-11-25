package io.github.inq_o.phonedoctor.feature.seniorhome.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.DISPLAY_NAME_MAX
import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.network.ApiException
import io.github.inq_o.phonedoctor.core.network.isAppCheckFailure
import io.github.inq_o.phonedoctor.core.packages.InstalledApps
import io.github.inq_o.phonedoctor.core.packages.StrongMode
import io.github.inq_o.phonedoctor.feature.seniorsites.api.SiteAlertsFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** 설정에서 한 일의 결과 */
enum class SettingsResult { NAME_SAVED, DELETED, NETWORK, APP_CHECK, BUSY, FAILED }

data class SeniorSettingsUiState(
    /** 가족 연결을 쓸 수 있는 빌드 (이름·가족·지우기를 보인다) */
    val familyAvailable: Boolean,
    /** 사이트 광고 알림(US10)을 쓰는 빌드 */
    val siteAlertsAvailable: Boolean = SiteAlertsFeature.ENABLED,
    /** 가족에게 보이는 이름. 정하지 않았으면 null ("부모님") */
    val displayName: String? = null,
    val working: Boolean = false,
    val result: SettingsResult? = null,
    /** 강력 모드(US8). 이 앱이 기기 소유자일 때만 (공장 초기화한 시연용 기기) */
    val strongMode: StrongModeUi? = null,
)

data class SuspendedApp(val packageName: String, val label: String)

data class StrongModeUi(val unknownSourcesBlocked: Boolean, val suspended: List<SuspendedApp>)

/** S7 설정 */
@HiltViewModel
class SeniorSettingsViewModel @Inject constructor(
    private val family: FamilyRepository,
    private val strongMode: StrongMode,
    private val apps: InstalledApps,
) : ViewModel() {
    private val state = MutableStateFlow(SeniorSettingsUiState(familyAvailable = family.available))
    val uiState: StateFlow<SeniorSettingsUiState> = state.asStateFlow()

    init {
        if (family.available) viewModelScope.launch { family.displayName.collect { n -> state.update { it.copy(displayName = n) } } }
    }

    /** 기기 정책 조회는 바인더 호출이라 기본 스레드 밖에서. 시험에서 바꾼다 */
    internal var io: CoroutineDispatcher = Dispatchers.IO

    /** 화면이 보일 때마다 (정지한 앱이 그 사이 지워졌을 수 있다) */
    fun refreshStrongMode() {
        viewModelScope.launch(io) {
            val ui = if (!strongMode.active) {
                null
            } else {
                StrongModeUi(
                    unknownSourcesBlocked = strongMode.unknownSourcesBlocked,
                    suspended = strongMode.suspendedPackages().map { SuspendedApp(it, apps.label(it) ?: it) },
                )
            }
            state.update { it.copy(strongMode = ui) }
        }
    }

    /** 출처를 알 수 없는 앱 설치 막기 (FR-032) */
    fun setUnknownSourcesBlocked(blocked: Boolean) {
        viewModelScope.launch(io) {
            strongMode.setUnknownSourcesBlocked(blocked)
            refreshStrongMode()
        }
    }

    /** 정지한 앱을 다시 쓰게 한다 (US8 "정지된 앱 목록에서 해제") */
    fun unsuspend(packageName: String) {
        viewModelScope.launch(io) {
            strongMode.unsuspend(packageName)
            refreshStrongMode()
        }
    }

    /** 이름 바꾸기. 1~20자 (앞뒤 빈칸은 뺀다) */
    fun rename(name: String) {
        val trimmed = name.trim()
        if (state.value.working || trimmed.isEmpty() || trimmed.length > DISPLAY_NAME_MAX) return
        run(SettingsResult.NAME_SAVED) { family.setDisplayName(trimmed) }
    }

    /** 모든 데이터 지우기 (서버의 이 폰 정보·연결·요청, Firebase 익명 사용자) */
    fun deleteAll() = run(SettingsResult.DELETED) { family.deleteAllData() }

    fun consumeResult() {
        state.update { it.copy(result = null) }
    }

    private fun run(success: SettingsResult, block: suspend () -> Unit) {
        if (state.value.working) return
        state.update { it.copy(working = true, result = null) }
        viewModelScope.launch {
            val result = try {
                block()
                success
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                // 망 문제가 아닌 App Check 실패는 "인터넷 없음"과 따로 안내한다
                if (e.isAppCheckFailure()) SettingsResult.APP_CHECK else SettingsResult.NETWORK
            } catch (e: ApiException) {
                when {
                    e.isAppCheckFailure() -> SettingsResult.APP_CHECK
                    e.status == 429 || e.status >= 500 -> SettingsResult.BUSY
                    else -> SettingsResult.FAILED
                }
            } catch (e: Exception) {
                SettingsResult.FAILED
            }
            state.update { it.copy(working = false, result = result) }
        }
    }
}
