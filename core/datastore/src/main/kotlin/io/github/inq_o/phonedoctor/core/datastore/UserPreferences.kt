package io.github.inq_o.phonedoctor.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
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

    /** 첫 실행에서 고른 쓰임새(O1): DeviceRole 이름. 고르기 전·역할 선택이 없던 판에서 설정을 끝낸 기기는 null */
    val chosenRole: Flow<String?> = store.data.map { it[CHOSEN_ROLE] }

    suspend fun setChosenRole(role: String) {
        store.edit { it[CHOSEN_ROLE] = role }
    }

    /** (시니어) 마지막으로 가족 서버에 상태를 보고한 시각 ms (`PUT /devices/{id}/status`, 6시간 간격) */
    val lastStatusReportAt: Flow<Long> = store.data.map { it[LAST_STATUS_REPORT] ?: 0L }

    suspend fun setLastStatusReportAt(at: Long) {
        store.edit { it[LAST_STATUS_REPORT] = at }
    }

    /** (시니어) 서버에서 받아 온 내 요청의 마지막 updatedAt. 다음 당겨 오기의 updatedSince */
    val alertPullCursor: Flow<String?> = store.data.map { it[ALERT_PULL_CURSOR] }

    suspend fun setAlertPullCursor(cursor: String?) {
        store.edit { if (cursor == null) it.remove(ALERT_PULL_CURSOR) else it[ALERT_PULL_CURSOR] = cursor }
    }

    /** 서버에 마지막으로 알린 앱 버전 (`appVersionCode`). 앱이 업데이트되면 다시 알린다 */
    val reportedAppVersion: Flow<Int?> = store.data.map { it[REPORTED_APP_VERSION] }

    suspend fun setReportedAppVersion(version: Int?) {
        store.edit { if (version == null) it.remove(REPORTED_APP_VERSION) else it[REPORTED_APP_VERSION] = version }
    }

    /** 가족에게 보이는 이 폰의 이름. 정하지 않았으면 null (서버 기본: 시니어 "부모님", 보호자 Google 이름) */
    val displayName: Flow<String?> = store.data.map { it[DISPLAY_NAME] }

    suspend fun setDisplayName(name: String?) {
        store.edit { if (name == null) it.remove(DISPLAY_NAME) else it[DISPLAY_NAME] = name }
    }

    /** (보호자) "주의 단계도 받기" (FR-028) */
    val notifyCaution: Flow<Boolean> = store.data.map { it[NOTIFY_CAUTION] ?: false }

    suspend fun setNotifyCaution(enabled: Boolean) {
        store.edit { it[NOTIFY_CAUTION] = enabled }
    }

    /** 모든 데이터 지우기를 시작했고 아직 끝나지 않음. 앱이 꺼졌다 켜지면 이어서 지운다 */
    val deletionPending: Flow<Boolean> = store.data.map { it[DELETION_PENDING] ?: false }

    suspend fun setDeletionPending(pending: Boolean) {
        store.edit { it[DELETION_PENDING] = pending }
    }

    suspend fun clearChosenRole() {
        store.edit { it.remove(CHOSEN_ROLE) }
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
        val CHOSEN_ROLE = stringPreferencesKey("chosenRole")
        val LAST_STATUS_REPORT = longPreferencesKey("lastStatusReportAt")
        val ALERT_PULL_CURSOR = stringPreferencesKey("alertPullCursor")
        val REPORTED_APP_VERSION = intPreferencesKey("reportedAppVersion")
        val DISPLAY_NAME = stringPreferencesKey("displayName")
        val NOTIFY_CAUTION = booleanPreferencesKey("notifyCaution")
        val DELETION_PENDING = booleanPreferencesKey("deletionPending")
    }
}

/** [role]은 core:model DeviceRole의 이름 (datastore는 model에 의존하지 않는다) */
data class RegisteredDevice(val deviceId: String, val role: String)
