package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.DISPLAY_NAME_MAX
import io.github.inq_o.phonedoctor.core.data.DeviceNotRegisteredException
import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.data.PhoneNameRepository
import io.github.inq_o.phonedoctor.core.data.GuardianAlertRepository
import io.github.inq_o.phonedoctor.core.data.ReauthAccountMismatchException
import io.github.inq_o.phonedoctor.core.data.RecentLoginRequiredException
import io.github.inq_o.phonedoctor.core.data.UserSettingsRepository
import io.github.inq_o.phonedoctor.core.model.FamilyLink
import io.github.inq_o.phonedoctor.core.model.LinkStatus
import io.github.inq_o.phonedoctor.core.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 연결 끊기·요청 취소 결과 (G4 연결된 폰). [error]가 있으면 실패 */
data class LinkMessage(val linkId: String, val seniorName: String, val wasPending: Boolean, val error: GuardianError? = null)

data class GuardianSettingsUiState(
    /** 연결된 폰 (연결됨 먼저, 그다음 수락 대기). 불러오기 전이면 null */
    val links: List<FamilyLink>? = null,
    val linksError: GuardianError? = null,
    /** 끊는 중인 연결 */
    val revoking: String? = null,
    val linkMessage: LinkMessage? = null,
    val notifyCaution: Boolean = false,
    /** [허용]로 답한 앱을 익명으로 알리기 (US7) */
    val falsePositiveFeedback: Boolean = true,
    /** 상대 폰에 보이는 내 이름. 정하지 않았으면 null (Google 계정 이름) */
    val displayName: String? = null,
    val working: Boolean = false,
    val saved: Boolean = false,
    val error: GuardianError? = null,
    /** 지우려면 Google로 다시 로그인해야 함 (오래전에 로그인함) */
    val needsReauth: Boolean = false,
    /** 다시 로그인할 때 처음과 다른 Google 계정을 고름 */
    val wrongAccount: Boolean = false,
    /** 다 지웠음. 화면이 역할 선택으로 넘어간 뒤 [GuardianSettingsViewModel.consumeDeleted] */
    val deleted: Boolean = false,
)

/** G4 보호자 설정 */
@HiltViewModel
class GuardianSettingsViewModel @Inject constructor(
    private val family: FamilyRepository,
    private val settings: UserSettingsRepository,
    private val alerts: GuardianAlertRepository,
    private val phoneNames: PhoneNameRepository,
) : ViewModel() {
    private val state = MutableStateFlow(GuardianSettingsUiState())
    val uiState: StateFlow<GuardianSettingsUiState> = state.asStateFlow()

    init {
        viewModelScope.launch { family.notifyCaution.collect { v -> state.update { it.copy(notifyCaution = v) } } }
        viewModelScope.launch { family.displayName.collect { v -> state.update { it.copy(displayName = v) } } }
        viewModelScope.launch { alerts.falsePositiveFeedback.collect { v -> state.update { it.copy(falsePositiveFeedback = v) } } }
    }

    private var linksJob: Job? = null

    /** 이 화면에서 끊은 연결: 끊기 전에 시작한 불러오기 결과가 늦게 와도 다시 보이지 않게 */
    private val revoked = mutableSetOf<String>()

    /** 화면이 보일 때마다 연결 목록을 다시 읽는다 (이전 불러오기는 취소: 응답 순서가 뒤바뀌지 않게) */
    fun loadLinks() {
        linksJob?.cancel()
        linksJob = viewModelScope.launch {
            try {
                val links = family.links()
                    .filter { it.status == LinkStatus.ACTIVE || it.status == LinkStatus.PENDING }
                    .filterNot { it.linkId in revoked }
                    .sortedWith(compareBy<FamilyLink> { it.status != LinkStatus.ACTIVE }.thenBy { it.createdAt })
                state.update { it.copy(links = links, linksError = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(linksError = e.toGuardianError()) }
            }
        }
    }

    /** 상대 폰 표시 이름 (이 폰에만 저장, ux-flows §2). 비우면 지운다 */
    fun setPhoneName(linkId: String, name: String?) {
        viewModelScope.launch { phoneNames.setChosenName(linkId, name) }
    }

    /** 연결 끊기·수락 전 요청 취소 (G4 확인창 뒤) */
    fun revoke(link: FamilyLink) {
        if (state.value.revoking != null) return
        state.update { it.copy(revoking = link.linkId, linkMessage = null) }
        linksJob?.cancel()
        viewModelScope.launch {
            var deviceGone = false
            val error = try {
                family.revokeLink(link.linkId)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: DeviceNotRegisteredException) {
                // 서버가 이 기기를 지웠으면 연결도 모두 없다
                deviceGone = true
                null
            } catch (e: ApiException) {
                // 이미 없는 연결이면 끊은 것과 같다
                if (e.status == 404) null else e.toGuardianError()
            } catch (e: Exception) {
                e.toGuardianError()
            }
            val pending = link.status == LinkStatus.PENDING
            if (error == null) revoked += link.linkId
            state.update { s ->
                s.copy(
                    links = if (error != null) s.links else if (deviceGone) emptyList() else s.links?.filterNot { it.linkId == link.linkId },
                    revoking = null,
                    linkMessage = LinkMessage(link.linkId, link.seniorName, pending, error),
                )
            }
        }
    }

    fun setNotifyCaution(enabled: Boolean) = run(saved = true) { family.setNotifyCaution(enabled) }

    /** 이 폰 설정만 바꾼다 (서버에 보내지 않음) */
    fun setFalsePositiveFeedback(enabled: Boolean) {
        viewModelScope.launch { alerts.setFalsePositiveFeedback(enabled) }
    }

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
        state.update { it.copy(working = true, error = null, wrongAccount = false) }
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
            } catch (e: ReauthAccountMismatchException) {
                state.update { it.copy(working = false, wrongAccount = true) }
            } catch (e: Exception) {
                state.update { it.copy(working = false, error = e.toGuardianError()) }
            }
        }
    }
}
