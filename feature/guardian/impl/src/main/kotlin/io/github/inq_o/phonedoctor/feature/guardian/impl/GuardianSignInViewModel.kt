package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SignInUiState(
    val busy: Boolean = false,
    val error: SignInError? = null,
    /** 로그인을 마침. 화면이 다음으로 넘어간 뒤 [GuardianSignInViewModel.consumeSignedIn] */
    val signedIn: Boolean = false,
)

/**
 * O-G1. 계정 고르기(Credential Manager)는 Activity가 필요해 화면이 하고, 받은 ID 토큰으로 Firebase 로그인은 여기서 한다.
 * 서버에는 아직 아무것도 보내지 않는다 (기기 등록은 코드를 넣을 때)
 */
@HiltViewModel
class GuardianSignInViewModel @Inject constructor(private val family: FamilyRepository) : ViewModel() {
    private val state = MutableStateFlow(SignInUiState())
    val uiState: StateFlow<SignInUiState> = state.asStateFlow()

    /** [Google로 로그인]을 누름. 이미 진행 중이면 false (계정 고르기 창을 두 번 띄우지 않게) */
    fun start(): Boolean {
        if (state.value.busy) return false
        state.value = SignInUiState(busy = true)
        return true
    }

    fun onToken(idToken: String) {
        viewModelScope.launch {
            try {
                family.signInGuardian(idToken)
                state.value = SignInUiState(busy = true, signedIn = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.value = SignInUiState(error = e.toSignInError())
            }
        }
    }

    /** 계정 고르기가 실패하거나 닫힘 */
    fun onFailed(e: Throwable) {
        state.value = SignInUiState(error = e.toSignInError())
    }

    /** 화면이 사라져 계정 고르기를 기다리던 작업이 취소됨. 다시 누를 수 있게 */
    fun onAbandoned() {
        state.update { if (it.signedIn) it else SignInUiState() }
    }

    fun consumeSignedIn() {
        state.update { it.copy(signedIn = false) }
    }
}
