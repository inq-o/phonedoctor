package io.github.inq_o.phonedoctor.feature.seniorsites.impl

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.SiteAlertRepository
import io.github.inq_o.phonedoctor.core.model.SiteAlert
import io.github.inq_o.phonedoctor.core.notifications.Notifier
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** S6 사이트 광고 알림 목록 (알림 접근을 허용했을 때) */
@HiltViewModel
class SitesViewModel @Inject constructor(repository: SiteAlertRepository) : ViewModel() {
    /** 처음 읽기 전에는 null */
    val sites: StateFlow<List<SiteAlert>?> = repository.alerts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

/** 광고 알림을 보내는 사이트 하나 */
@HiltViewModel(assistedFactory = SiteAlertViewModel.Factory::class)
class SiteAlertViewModel @AssistedInject constructor(
    @Assisted private val site: String,
    private val repository: SiteAlertRepository,
    private val notifier: Notifier,
    private val saved: SavedStateHandle,
) : ViewModel() {
    /** 처음 읽기 전에는 [SiteAlertState.Loading], 없는 사이트면 [SiteAlertState.Missing] */
    val alert: StateFlow<SiteAlertState> = repository.alert(site)
        .map { if (it == null) SiteAlertState.Missing else SiteAlertState.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SiteAlertState.Loading)

    init {
        // 화면으로 봤으니 알림은 지운다
        notifier.cancelSite(site)
    }

    /** 알림을 눌러 들어온 요청([openRequest])으로 아직 설정을 열지 않았으면 true. 한 요청에 한 번만 연다 (화면 회전·복원에도) */
    fun shouldAutoOpen(openRequest: String?): Boolean = openRequest != null && saved.get<String>(KEY_CONSUMED) != openRequest

    /** 그 사이트 알림 설정 화면을 열었음. 돌아오면([onResumed]) 그때부터 오는 알림으로 스위치가 꺼졌는지 본다 */
    fun onSettingsOpened(openRequest: String? = null) {
        openRequest?.let { saved[KEY_CONSUMED] = it }
        saved[KEY_AWAY] = true
    }

    /** 화면이 다시 보임: 설정에서 돌아왔으면 그 시각을 적는다 (스위치를 끄는 동안 온 알림은 "아직 켜져 있어요"로 세지 않게) */
    fun onResumed() {
        if (saved.get<Boolean>(KEY_AWAY) != true) return
        saved[KEY_AWAY] = false
        viewModelScope.launch { repository.markMuted(site) }
    }

    fun allow() {
        viewModelScope.launch { repository.allow(site) }
    }

    @AssistedFactory
    interface Factory {
        fun create(site: String): SiteAlertViewModel
    }

    private companion object {
        const val KEY_CONSUMED = "consumedOpenRequest"
        const val KEY_AWAY = "settingsOpen"
    }
}

sealed interface SiteAlertState {
    data object Loading : SiteAlertState

    data object Missing : SiteAlertState

    data class Ready(val alert: SiteAlert) : SiteAlertState
}
