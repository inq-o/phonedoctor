package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.DISPLAY_NAME_MAX
import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.data.RecentLoginRequiredException
import io.github.inq_o.phonedoctor.core.data.UserSettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GuardianSettingsUiState(
    val notifyCaution: Boolean = false,
    /** 부모님 폰에 보이는 내 이름. 정하지 않았으면 null (Google 계정 이름) */
    val displayName: String? = null,
    val working: Boolean = false,
    val saved: Boolean = false,
    val error: GuardianError? = null,
    /** 지우려면 Google로 다시 로그인해야 함 (오래전에 로그인함) */
    val needsReauth: Boolean = false,
    /** 다 지웠음. 화면이 역할 선택으로 넘어간 뒤 [GuardianSettingsViewModel.consumeDeleted] */
    val deleted: Boolean = false,
)

/** G4 보호자 설정 */
@HiltViewModel
class GuardianSettingsViewModel @Inject constructor(
    private val family: FamilyRepository,
    private val settings: UserSettingsRepository,
) : ViewModel() {
    private val state = MutableStateFlow(GuardianSettingsUiState())
    val uiState: StateFlow<GuardianSettingsUiState> = state.asStateFlow()

    init {
        viewModelScope.launch { family.notifyCaution.collect { v -> state.update { it.copy(notifyCaution = v) } } }
        viewModelScope.launch { family.displayName.collect { v -> state.update { it.copy(displayName = v) } } }
    }

    fun setNotifyCaution(enabled: Boolean) = run(saved = true) { family.setNotifyCaution(enabled) }

    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > DISPLAY_NAME_MAX) return
        run(saved = true) { family.setDisplayName(trimmed) }
    }

    /** 모든 데이터 지우기: 서버의 기기·연결, Firebase 사용자. 끝나면 다음 실행은 역할 선택부터 */
    fun deleteAll() = run(saved = false) { deleteEverything() }

    /** [Google로 다시 로그인]을 누름. 이미 진행 중이면 false (로그인 창을 두 번 띄우지 않게) */
    fun startReauth(): Boolean {
        if (state.value.working) return false
        state.update { it.copy(working = true, error = null) }
        return true
    }

    /** 다시 로그인한 토큰으로 확인한 뒤 이어서 지운다 ([startReauth]가 이미 진행 중으로 표시함) */
    fun onReauthToken(idToken: String) = launchWork(saved = false) {
        family.reauthenticateGuardian(idToken)
        deleteEverything()
    }

    /** 다시 로그인하는 창이 실패했거나 닫힘. 닫았으면 안내 없이 처음처럼 */
    fun onReauthFailed(e: Throwable) {
        state.update { it.copy(working = false, error = if (e.toSignInError() == null) null else GuardianError.UNKNOWN) }
    }

    fun consumeDeleted() {
        state.update { it.copy(deleted = false) }
    }

    private suspend fun deleteEverything() {
        family.deleteAllData()
        settings.clearChosenRole()
        state.update { it.copy(deleted = true, needsReauth = false) }
    }

    private fun run(saved: Boolean, block: suspend () -> Unit) {
        if (state.value.working) return
        state.update { it.copy(working = true, error = null, saved = false) }
        launchWork(saved, block)
    }

    private fun launchWork(saved: Boolean, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                state.update { it.copy(working = false, saved = saved) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RecentLoginRequiredException) {
                state.update { it.copy(working = false, needsReauth = true) }
            } catch (e: Exception) {
                state.update { it.copy(working = false, error = e.toGuardianError()) }
            }
        }
    }
}
