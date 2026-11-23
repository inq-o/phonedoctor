package io.github.inq_o.phonedoctor.feature.onboarding.impl

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.inq_o.phonedoctor.core.data.UserSettingsRepository
import io.github.inq_o.phonedoctor.core.model.DeviceRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** O1. 고른 역할을 저장한 뒤 [chosen]으로 알린다. 서버에는 아무것도 보내지 않는다 */
@HiltViewModel
class RoleChoiceViewModel @Inject constructor(private val settings: UserSettingsRepository) : ViewModel() {
    private val state = MutableStateFlow<DeviceRole?>(null)

    /** 저장을 마친 역할. 화면이 다음으로 넘어간 뒤 [consumeChosen] */
    val chosen: StateFlow<DeviceRole?> = state.asStateFlow()

    private var saving = false

    fun choose(role: DeviceRole) {
        // 두 버튼을 빠르게 연달아 눌러도 한 번만
        if (saving) return
        saving = true
        viewModelScope.launch {
            settings.chooseRole(role)
            state.value = role
        }
    }

    /** 다음 화면으로 넘겼음. 넘어가는 동안 다시 눌러도 저장하지 않는다 */
    fun consumeChosen() {
        state.value = null
    }

    /** 화면이 (다시) 보임: 뒤로 돌아오면 다시 고를 수 있다. 화면이 사라진 뒤 끝난 저장의 결과는 버린다 */
    fun onShown() {
        state.value = null
        saving = false
    }
}
