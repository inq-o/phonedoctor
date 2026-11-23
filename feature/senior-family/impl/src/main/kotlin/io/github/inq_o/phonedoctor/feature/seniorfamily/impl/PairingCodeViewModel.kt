package io.github.inq_o.phonedoctor.feature.seniorfamily.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.model.LinkStatus
import io.github.inq_o.phonedoctor.core.model.PairingCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

sealed interface PairingCodeUiState {
    data object Loading : PairingCodeUiState

    /** [expired]: 10분이 지나 보호자가 더는 넣을 수 없음. 시니어 화면에는 남은 시간을 세지 않고(ux-flows 원칙 6) 지난 뒤에만 알린다 */
    data class Ready(val code: PairingCode, val expired: Boolean = false) : PairingCodeUiState

    data class Failed(val error: FamilyError) : PairingCodeUiState
}

/**
 * O-S4 연결 코드. 화면이 열리면 새 코드를 받는다(이때 처음 로그인·기기 등록).
 * 화면이 보이는 동안 연결 목록을 주기적으로 확인해, 보호자가 코드를 넣으면 푸시가 오지 않아도 연결 확인(S3)으로 넘어간다
 */
@HiltViewModel
class PairingCodeViewModel @Inject constructor(private val family: FamilyRepository) : ViewModel() {
    private val state = MutableStateFlow<PairingCodeUiState>(PairingCodeUiState.Loading)
    val uiState: StateFlow<PairingCodeUiState> = state.asStateFlow()

    private val requested = MutableStateFlow<String?>(null)

    /** 보호자가 코드를 넣어 생긴 확인 대기 연결. 화면이 S3로 넘어간 뒤 [onLinkOpened]로 지운다 */
    val linkRequested: StateFlow<String?> = requested.asStateFlow()

    internal var now: () -> Instant = Instant::now

    private var loading: Job? = null
    private var watching: Job? = null

    init {
        newCode()
    }

    /** 처음, 실패 뒤 다시, 만료 뒤 새 코드. 받는 중에 다시 누르면 무시한다 */
    fun newCode() {
        if (loading?.isActive == true) return
        state.value = PairingCodeUiState.Loading
        loading = viewModelScope.launch {
            state.value = try {
                PairingCodeUiState.Ready(family.createPairingCode())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PairingCodeUiState.Failed(e.toFamilyError())
            }
        }
    }

    /** 화면이 보일 때만 확인한다 */
    fun startWatching() {
        if (watching?.isActive == true) return
        watching = viewModelScope.launch {
            while (true) {
                delay(POLL_INTERVAL_MS)
                check()
            }
        }
    }

    fun stopWatching() {
        watching?.cancel()
        watching = null
    }

    fun onLinkOpened() {
        requested.value = null
    }

    internal suspend fun check() {
        val ready = state.value as? PairingCodeUiState.Ready ?: return
        if (!ready.expired && !now().isBefore(ready.code.expiresAt)) state.value = ready.copy(expired = true)
        // 망이 끊기는 등 실패는 다음 확인 때 다시 (푸시로도 온다)
        val links = try {
            family.links()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return
        }
        // 확인 대기가 여럿이면 가장 최근 요청부터. S3가 이름을 보여 주므로 누구의 요청인지 시니어가 보고 고른다
        links.filter { it.status == LinkStatus.PENDING }.maxByOrNull { it.createdAt }?.let { requested.value = it.linkId }
    }

    private companion object {
        const val POLL_INTERVAL_MS = 5_000L
    }
}
