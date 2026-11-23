package io.github.inq_o.phonedoctor.feature.seniorhome.impl

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.data.ScanScheduler
import io.github.inq_o.phonedoctor.core.data.SymptomRepository
import io.github.inq_o.phonedoctor.core.model.FamilyLink
import io.github.inq_o.phonedoctor.core.model.LinkStatus
import io.github.inq_o.phonedoctor.core.model.ScanRun
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import io.github.inq_o.phonedoctor.core.notifications.Notifier
import io.github.inq_o.phonedoctor.core.usage.UsageAccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 홈에 보이는 가족 연결. [available]이 false인 빌드(출시)는 [가족] 버튼도 숨긴다.
 * [pending]은 시니어가 아직 답하지 않은 요청 (푸시를 놓쳐도 홈에서 보이게, push-messages.md "누락 대비")
 */
data class HomeFamily(
    val available: Boolean,
    val guardians: List<String> = emptyList(),
    val pending: FamilyLink? = null,
)

/** 기기 설정에서 바뀌는 상태. 화면이 다시 보일 때마다 읽는다 */
data class DevicePermissions(val usageAccess: Boolean, val notifications: Boolean)

sealed interface SeniorHomeUiState {
    data object Loading : SeniorHomeUiState

    /**
     * 사용 기록 보기가 꺼져 있어 광고를 찾지 못하는 상태. 정상으로 보이게 두지 않는다.
     * 이미 열린 요청은 권한 없이도 지울 수 있으므로 함께 보여 준다
     */
    data class UsageAccessOff(val alerts: List<SymptomAlert>) : SeniorHomeUiState

    data class Ready(
        val alerts: List<SymptomAlert>,
        val lastScan: ScanRun?,
        val scanning: Boolean,
        val notificationsOn: Boolean,
    ) : SeniorHomeUiState
}

@HiltViewModel
class SeniorHomeViewModel @Inject constructor(
    repository: SymptomRepository,
    private val usageAccess: UsageAccess,
    private val notifier: Notifier,
    private val scheduler: ScanScheduler,
    private val familyRepository: FamilyRepository,
) : ViewModel() {
    private val permissions = MutableStateFlow(readPermissions())

    private val familyState = MutableStateFlow(HomeFamily(familyRepository.available))
    val family: StateFlow<HomeFamily> = familyState.asStateFlow()
    private var familyLoad: Job? = null

    val uiState: StateFlow<SeniorHomeUiState> =
        combine(repository.openAlerts, repository.latestScan, repository.scanning, permissions) { alerts, last, scanning, perm ->
            if (!perm.usageAccess) SeniorHomeUiState.UsageAccessOff(alerts) else SeniorHomeUiState.Ready(alerts, last, scanning, perm.notifications)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SeniorHomeUiState.Loading)

    val usageAccessSettingsIntents: List<Intent> get() = usageAccess.settingsIntents()

    /** 화면이 다시 보일 때: 권한 상태를 다시 읽고, 방금 켰으면 바로 한 번 살펴본다 */
    fun refresh() {
        val before = permissions.value
        val now = readPermissions()
        permissions.value = now
        if (!before.usageAccess && now.usageAccess) scheduler.scanNow("permission_granted")
        refreshFamily()
    }

    /** 연결을 시작하기 전이면 서버를 부르지 않는다 (FamilyRepository.links). 실패하면 보이던 대로 둔다 */
    private fun refreshFamily() {
        if (!familyRepository.available || familyLoad?.isActive == true) return
        familyLoad = viewModelScope.launch {
            val links = try {
                familyRepository.links()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            familyState.value = HomeFamily(
                available = true,
                guardians = links.filter { it.status == LinkStatus.ACTIVE }.sortedBy { it.createdAt }.map { it.guardianName },
                pending = links.filter { it.status == LinkStatus.PENDING }.maxByOrNull { it.createdAt },
            )
        }
    }

    fun scanNow() = scheduler.scanNow("manual")

    private fun readPermissions() = DevicePermissions(usageAccess.isGranted(), notifier.canNotify())
}
