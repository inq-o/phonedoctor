package io.github.inq_o.phonedoctor.feature.seniorhome.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.DISPLAY_NAME_MAX
import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.network.ApiException
import io.github.inq_o.phonedoctor.feature.seniorsites.api.SiteAlertsFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** 설정에서 한 일의 결과 */
enum class SettingsResult { NAME_SAVED, DELETED, NETWORK, BUSY, FAILED }

data class SeniorSettingsUiState(
    /** 가족 연결을 쓸 수 있는 빌드 (이름·가족·지우기를 보인다) */
    val familyAvailable: Boolean,
    /** 사이트 광고 알림(US10)을 쓰는 빌드 */
    val siteAlertsAvailable: Boolean = SiteAlertsFeature.ENABLED,
    /** 가족에게 보이는 이름. 정하지 않았으면 null ("부모님") */
    val displayName: String? = null,
    val working: Boolean = false,
    val result: SettingsResult? = null,
)

/** S7 설정 */
@HiltViewModel
class SeniorSettingsViewModel @Inject constructor(private val family: FamilyRepository) : ViewModel() {
    private val state = MutableStateFlow(SeniorSettingsUiState(familyAvailable = family.available))
    val uiState: StateFlow<SeniorSettingsUiState> = state.asStateFlow()

    init {
        if (family.available) viewModelScope.launch { family.displayName.collect { n -> state.update { it.copy(displayName = n) } } }
    }

    /** 이름 바꾸기. 1~20자 (앞뒤 빈칸은 뺀다) */
    fun rename(name: String) {
        val trimmed = name.trim()
        if (state.value.working || trimmed.isEmpty() || trimmed.length > DISPLAY_NAME_MAX) return
        run(SettingsResult.NAME_SAVED) { family.setDisplayName(trimmed) }
    }

    /** 모든 데이터 지우기 (서버의 이 폰 정보·연결·요청, Firebase 익명 사용자) */
    fun deleteAll() = run(SettingsResult.DELETED) { family.deleteAllData() }

    fun consumeResult() {
        state.update { it.copy(result = null) }
    }

    private fun run(success: SettingsResult, block: suspend () -> Unit) {
        if (state.value.working) return
        state.update { it.copy(working = true, result = null) }
        viewModelScope.launch {
            val result = try {
                block()
                success
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                SettingsResult.NETWORK
            } catch (e: ApiException) {
                if (e.status == 429 || e.status >= 500) SettingsResult.BUSY else SettingsResult.FAILED
            } catch (e: Exception) {
                SettingsResult.FAILED
            }
            state.update { it.copy(working = false, result = result) }
        }
    }
}
