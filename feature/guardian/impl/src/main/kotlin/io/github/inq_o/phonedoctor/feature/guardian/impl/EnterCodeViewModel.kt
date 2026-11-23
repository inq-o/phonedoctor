package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.data.GuardianSignInRequiredException
import io.github.inq_o.phonedoctor.core.data.RoleMismatchException
import io.github.inq_o.phonedoctor.core.model.FamilyLink
import io.github.inq_o.phonedoctor.core.network.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import javax.inject.Inject

/** 코드 입력이 실패한 까닭 (openapi acceptPairingCode) */
enum class CodeError { NOT_FOUND, EXPIRED, ALREADY_LINKED, LIMIT, TOO_MANY, NOT_PAIRING_QR, SCAN_FAILED, NETWORK, BUSY, WRONG_ROLE, UNKNOWN }

data class EnterCodeUiState(
    val code: String = "",
    val busy: Boolean = false,
    val error: CodeError? = null,
    /** [CodeError.TOO_MANY]일 때 서버가 알려 준 기다릴 시간(초) */
    val retryAfterSeconds: Long? = null,
    /** 연결 요청을 보냄. 화면이 대기 화면으로 넘어간 뒤 [EnterCodeViewModel.consumeRequested] */
    val requested: FamilyLink? = null,
    /** 로그인이 풀려 있음 (Firebase 사용자가 지워짐 등). 로그인 화면으로 */
    val signInRequired: Boolean = false,
) {
    val canSubmit: Boolean get() = code.length == CODE_LENGTH && !busy
}

internal const val CODE_LENGTH = 6

/** O-G2. 시니어 폰의 숫자 6자리를 넣거나 QR을 읽어 연결을 요청한다 */
@HiltViewModel
class EnterCodeViewModel @Inject constructor(private val family: FamilyRepository) : ViewModel() {
    private val state = MutableStateFlow(EnterCodeUiState())
    val uiState: StateFlow<EnterCodeUiState> = state.asStateFlow()

    /** 숫자만, 6자리까지 (붙여 넣은 "482 913"도 받는다) */
    fun onCodeChange(input: String) {
        if (state.value.busy) return
        state.update { it.copy(code = input.filter { c -> c in '0'..'9' }.take(CODE_LENGTH), error = null, retryAfterSeconds = null) }
    }

    fun submit() {
        val s = state.value
        if (!s.canSubmit) return
        state.update { it.copy(busy = true, error = null, retryAfterSeconds = null) }
        viewModelScope.launch {
            try {
                val link = family.acceptPairingCode(s.code)
                state.update { it.copy(busy = false, code = "", requested = link) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: GuardianSignInRequiredException) {
                state.update { it.copy(busy = false, signInRequired = true) }
            } catch (e: Exception) {
                state.update {
                    it.copy(busy = false, error = e.toCodeError(), retryAfterSeconds = (e as? ApiException)?.retryAfterSeconds)
                }
            }
        }
    }

    /** QR을 읽음. 폰주치의 연결 QR이면 바로 보낸다 */
    fun onScanned(raw: String?) {
        if (state.value.busy) return
        val code = raw?.let(::pairingCodeFrom)
        if (code == null) {
            state.update { it.copy(error = CodeError.NOT_PAIRING_QR, retryAfterSeconds = null) }
            return
        }
        state.update { it.copy(code = code, error = null, retryAfterSeconds = null) }
        submit()
    }

    /** QR 읽기 화면을 띄우지 못함 (Play 서비스 없음, 모듈 내려받기 실패 등). 숫자로 넣게 안내 */
    fun onScanFailed() {
        state.update { it.copy(error = CodeError.SCAN_FAILED, retryAfterSeconds = null) }
    }

    fun consumeRequested() {
        state.update { it.copy(requested = null) }
    }

    fun consumeSignInRequired() {
        state.update { it.copy(signInRequired = false) }
    }
}

internal fun Throwable.toCodeError(): CodeError = when (this) {
    is ApiException -> when {
        status == 404 -> CodeError.NOT_FOUND
        status == 410 -> CodeError.EXPIRED
        status == 409 && slug == "already-linked" -> CodeError.ALREADY_LINKED
        status == 409 && slug == "guardian-limit" -> CodeError.LIMIT
        status == 429 -> CodeError.TOO_MANY
        status >= 500 -> CodeError.BUSY
        else -> CodeError.UNKNOWN
    }
    is IOException -> CodeError.NETWORK
    is RoleMismatchException -> CodeError.WRONG_ROLE
    else -> CodeError.UNKNOWN
}

private val SIX_DIGITS = Regex("^[0-9]{6}$")

/** 시니어 화면 QR의 내용(서버 qrPayload "phonedoctor://pair?c=482913")에서 코드를 꺼낸다. 숫자 6자리만 담긴 QR도 받는다 */
internal fun pairingCodeFrom(raw: String): String? {
    val text = raw.trim()
    if (SIX_DIGITS.matches(text)) return text
    val uri = try {
        URI(text)
    } catch (e: URISyntaxException) {
        return null
    }
    if (!"phonedoctor".equals(uri.scheme, ignoreCase = true) || !"pair".equals(uri.host, ignoreCase = true)) return null
    val code = uri.rawQuery?.split('&')?.firstNotNullOfOrNull { part ->
        val pair = part.split('=', limit = 2)
        if (pair.size == 2 && pair[0] == "c") pair[1] else null
    }
    return code?.takeIf { SIX_DIGITS.matches(it) }
}
