package io.github.inq_o.phonedoctor.feature.guardian.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.data.GuardianAlertRepository
import io.github.inq_o.phonedoctor.core.model.FamilyAlert
import io.github.inq_o.phonedoctor.core.model.FamilyAlertStatus
import io.github.inq_o.phonedoctor.core.model.FamilyLink
import io.github.inq_o.phonedoctor.core.model.LinkStatus
import io.github.inq_o.phonedoctor.core.notifications.RequestChanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

/** 보호자 홈의 연결 하나. [contactLost]: 시니어 폰의 검사 보고가 48시간 넘게 없음 (ux-flows F6) */
data class GuardianLink(val link: FamilyLink, val contactLost: Boolean = false)

sealed interface GuardianHomeUiState {
    data object Loading : GuardianHomeUiState

    data class Failed(val error: GuardianError) : GuardianHomeUiState

    /** [links]: 연결됨 먼저, 그다음 수락을 기다리는 연결. 연결 끊기·요청 취소는 G4 설정에서 (ux-flows §4 G4) */
    data class Ready(
        val links: List<GuardianLink>,
        /** 답을 기다리는 요청 (열려 있고 아무도 답하지 않음), 최근 것부터 */
        val requests: List<FamilyAlert> = emptyList(),
        /** 지난 요청(답했거나 끝난 것)이 있어 [지난 요청 보기]를 보인다 */
        val hasHistory: Boolean = false,
    ) : GuardianHomeUiState
}

/** G1 보호자 홈. 화면이 보일 때마다 연결 목록을 다시 불러온다 (놓친 LINK_* 푸시 대비, push-messages.md §1) */
@HiltViewModel
class GuardianHomeViewModel @Inject constructor(
    private val family: FamilyRepository,
    private val alerts: GuardianAlertRepository,
    changes: RequestChanges,
) : ViewModel() {
    private val state = MutableStateFlow<GuardianHomeUiState>(GuardianHomeUiState.Loading)
    val uiState: StateFlow<GuardianHomeUiState> = state.asStateFlow()

    internal var now: () -> Instant = Instant::now

    private var loading: Job? = null

    init {
        // 홈을 보는 동안 새 요청·응답·결과가 오면 다시 읽는다
        viewModelScope.launch { changes.changes.collect { refresh() } }
    }

    fun refresh() {
        if (loading?.isActive == true) return
        if (state.value is GuardianHomeUiState.Failed) state.value = GuardianHomeUiState.Loading
        loading = viewModelScope.launch {
            // 연결과 요청을 함께 불러온다. 요청만 실패하면 연결은 보여 주고 요청은 보이던 대로 둔다
            // 열린 요청만 받는다 (목록이 길어도 답할 것이 잘리지 않게)
            val requests = async { runCatchingNonCancel { alerts.alerts(FamilyAlertStatus.OPEN) } }
            val links = try {
                family.links()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                requests.cancel()
                // 보이던 목록이 있으면 그대로 둔다 (다음에 다시)
                if (state.value !is GuardianHomeUiState.Ready) state.value = GuardianHomeUiState.Failed(e.toGuardianError())
                return@launch
            }
            val loaded = requests.await()
            val current = state.value as? GuardianHomeUiState.Ready
            val shown = visible(links)
            state.value = GuardianHomeUiState.Ready(
                shown,
                // 요청만 못 읽었으면 보이던 요청을 두되, 지금 보이는 연결로 받은 것만 (설정에서 끊은 폰의 요청이 남지 않게)
                requests = loaded?.filter { it.answerable } ?: current?.requests.orEmpty().filter { r ->
                    shown.any { it.link.status == LinkStatus.ACTIVE && it.link.linkId in r.linkIds }
                },
                // 연결이 있으면 지난 요청(답한 것·알림 전용·끝난 것)을 볼 수 있다
                hasHistory = shown.any { it.link.status == LinkStatus.ACTIVE },
            )
        }
    }

    private fun visible(links: List<FamilyLink>): List<GuardianLink> {
        val at = now()
        return links
            .filter { it.status == LinkStatus.ACTIVE || it.status == LinkStatus.PENDING }
            .sortedWith(compareBy<FamilyLink> { it.status != LinkStatus.ACTIVE }.thenBy { it.createdAt })
            .map { GuardianLink(it, contactLost = it.isContactLost(at)) }
    }
}

/** 실패는 null로 (취소는 그대로 던진다) */
internal suspend fun <T> runCatchingNonCancel(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/** 서버의 CONTACT_LOST 판정(Jobs.kt)과 같은 기준: max(마지막 검사 보고, 연결 확정)에서 48시간 */
internal val CONTACT_LOST_AFTER: Duration = Duration.ofHours(48)

/**
 * 시니어 폰이 6시간마다 보고하므로(T055a) 보고가 한 번도 없으면 연결한 뒤 폰주치의가 돌지 않은 것이다.
 * CONTACT_LOST 알림을 누르고 들어온 홈과 같은 판단이 되게 서버 규칙 그대로 잰다
 */
internal fun FamilyLink.isContactLost(now: Instant): Boolean {
    if (status != LinkStatus.ACTIVE) return false
    val since = listOfNotNull(lastCheckedAt, activatedAt).maxOrNull() ?: return false
    return Duration.between(since, now) >= CONTACT_LOST_AFTER
}
