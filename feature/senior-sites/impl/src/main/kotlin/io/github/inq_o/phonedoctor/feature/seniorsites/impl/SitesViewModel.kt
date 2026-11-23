package io.github.inq_o.phonedoctor.feature.seniorsites.impl

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
) : ViewModel() {
    /** 처음 읽기 전에는 [SiteAlertState.Loading], 없는 사이트면 [SiteAlertState.Missing] */
    val alert: StateFlow<SiteAlertState> = repository.alert(site)
        .map { if (it == null) SiteAlertState.Missing else SiteAlertState.Ready(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), SiteAlertState.Loading)

    init {
        // 화면으로 봤으니 알림은 지운다
        notifier.cancelSite(site)
    }

    /** 그 사이트 알림 설정 화면을 연다 (화면이 연다). 연 시각부터 온 알림으로 스위치가 꺼졌는지 본다 */
    fun onSettingsOpened() {
        viewModelScope.launch { repository.markMuted(site) }
    }

    fun allow() {
        viewModelScope.launch { repository.allow(site) }
    }

    @AssistedFactory
    interface Factory {
        fun create(site: String): SiteAlertViewModel
    }
}

sealed interface SiteAlertState {
    data object Loading : SiteAlertState

    data object Missing : SiteAlertState

    data class Ready(val alert: SiteAlert) : SiteAlertState
}
