package io.github.inq_o.phonedoctor.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** 기기 설정 (data-model.md §5). 지금은 온보딩·고지 동의만 */
class UserPreferences @Inject constructor(private val store: DataStore<Preferences>) {
    val onboardingDone: Flow<Boolean> = store.data.map { it[ONBOARDING_DONE] ?: false }

    /** 동의한 고지 문구 판. 문구가 바뀌면 다시 받는다 */
    val disclosureAcceptedVersion: Flow<Int> = store.data.map { it[DISCLOSURE_VERSION] ?: 0 }

    /** 서버에 등록한 이 기기의 ID와 역할 (가족 연결을 시작할 때 생김). 없으면 null */
    val registeredDevice: Flow<RegisteredDevice?> = store.data.map { prefs ->
        val id = prefs[DEVICE_ID] ?: return@map null
        RegisteredDevice(id, prefs[DEVICE_ROLE] ?: return@map null)
    }

    suspend fun setRegisteredDevice(device: RegisteredDevice?) {
        store.edit {
            if (device == null) {
                it.remove(DEVICE_ID)
                it.remove(DEVICE_ROLE)
            } else {
                it[DEVICE_ID] = device.deviceId
                it[DEVICE_ROLE] = device.role
            }
        }
    }

    /** 기기 등록을 보냈는데 아직 응답을 못 받은 역할. 응답 유실 뒤에도 모든 데이터 지우기가 서버 기기를 찾게 남긴다 */
    val pendingRegistrationRole: Flow<String?> = store.data.map { it[PENDING_ROLE] }

    suspend fun setPendingRegistrationRole(role: String?) {
        store.edit { if (role == null) it.remove(PENDING_ROLE) else it[PENDING_ROLE] = role }
    }

    suspend fun setOnboardingDone(done: Boolean) {
        store.edit { it[ONBOARDING_DONE] = done }
    }

    suspend fun setDisclosureAcceptedVersion(version: Int) {
        store.edit { it[DISCLOSURE_VERSION] = version }
    }

    private companion object {
        val ONBOARDING_DONE = booleanPreferencesKey("onboardingDone")
        val DISCLOSURE_VERSION = intPreferencesKey("disclosureAcceptedVersion")
        val DEVICE_ID = stringPreferencesKey("deviceId")
        val DEVICE_ROLE = stringPreferencesKey("deviceRole")
        val PENDING_ROLE = stringPreferencesKey("pendingRegistrationRole")
    }
}

/** [role]은 core:model DeviceRole의 이름 (datastore는 model에 의존하지 않는다) */
data class RegisteredDevice(val deviceId: String, val role: String)
