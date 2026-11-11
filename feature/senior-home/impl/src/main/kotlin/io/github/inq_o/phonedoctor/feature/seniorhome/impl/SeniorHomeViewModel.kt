package io.github.inq_o.phonedoctor.feature.seniorhome.impl

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.ScanScheduler
import io.github.inq_o.phonedoctor.core.data.SymptomRepository
import io.github.inq_o.phonedoctor.core.model.ScanRun
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import io.github.inq_o.phonedoctor.core.notifications.Notifier
import io.github.inq_o.phonedoctor.core.usage.UsageAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** 기기 설정에서 바뀌는 상태. 화면이 다시 보일 때마다 읽는다 */
data class DevicePermissions(val usageAccess: Boolean, val notifications: Boolean)

sealed interface SeniorHomeUiState {
    data object Loading : SeniorHomeUiState

    /** 사용 기록 보기가 꺼져 있어 광고를 찾지 못하는 상태. 정상으로 보이게 두지 않는다 */
    data object UsageAccessOff : SeniorHomeUiState

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
) : ViewModel() {
    private val permissions = MutableStateFlow(readPermissions())

    val uiState: StateFlow<SeniorHomeUiState> =
        combine(repository.openAlerts, repository.latestScan, repository.scanning, permissions) { alerts, last, scanning, perm ->
            if (!perm.usageAccess) SeniorHomeUiState.UsageAccessOff else SeniorHomeUiState.Ready(alerts, last, scanning, perm.notifications)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SeniorHomeUiState.Loading)

    val usageAccessSettingsIntents: List<Intent> get() = usageAccess.settingsIntents()

    /** 화면이 다시 보일 때: 권한 상태를 다시 읽고, 방금 켰으면 바로 한 번 살펴본다 */
    fun refresh() {
        val before = permissions.value
        val now = readPermissions()
        permissions.value = now
        if (!before.usageAccess && now.usageAccess) scheduler.scanNow("permission_granted")
    }

    fun scanNow() = scheduler.scanNow("manual")

    private fun readPermissions() = DevicePermissions(usageAccess.isGranted(), notifier.canNotify())
}
