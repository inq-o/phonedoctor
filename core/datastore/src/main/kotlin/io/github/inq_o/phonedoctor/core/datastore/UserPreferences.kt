package io.github.inq_o.phonedoctor.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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

    /** 가족에게 보이는 이 폰의 이름. 정하지 않았으면 null (서버 기본: 시니어는 빈 값, 보호자는 Google 이름) */
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

    /** (보호자) [괜찮은 앱이에요]로 답한 앱을 익명으로 알려 판정을 고치는 데 쓰게 할지 (US7, 기본 켬, data-model §5) */
    val falsePositiveFeedback: Flow<Boolean> = store.data.map { it[FALSE_POSITIVE_FEEDBACK] ?: true }

    suspend fun setFalsePositiveFeedback(enabled: Boolean) {
        store.edit { it[FALSE_POSITIVE_FEEDBACK] = enabled }
    }

    /**
     * (보호자) 오탐 신고를 이미 맡긴 요청 (`요청ID:근거종류`). 같은 요청에 다시 답해도 한 번만 보낸다 (openapi: 서버는 키를 받지 않으므로 기기가 지킴).
     * 요청은 90일 뒤 서버에서 지워지므로 오래된 것은 앞에서부터 버린다
     */
    val reportedFalsePositives: Flow<Set<String>> = store.data.map { it[REPORTED_FALSE_POSITIVES] ?: emptySet() }

    /** 오탐 신고 세대: 끄거나 모든 데이터를 지울 때 올린다. 그 전에 맡긴 신고는 보내지 않는다 (지워도 되돌리지 않음) */
    val falsePositiveGeneration: Flow<Long> = store.data.map { it[FALSE_POSITIVE_GENERATION] ?: 0L }

    suspend fun bumpFalsePositiveGeneration() {
        store.edit { it[FALSE_POSITIVE_GENERATION] = (it[FALSE_POSITIVE_GENERATION] ?: 0L) + 1 }
    }

    /** 끄기와 세대 올리기를 한 번에 (사이에 앱이 꺼져 껐는데 세대는 그대로 남는 일이 없게) */
    suspend fun disableFalsePositiveFeedback() {
        store.edit {
            it[FALSE_POSITIVE_FEEDBACK] = false
            it[FALSE_POSITIVE_GENERATION] = (it[FALSE_POSITIVE_GENERATION] ?: 0L) + 1
        }
    }

    /** 모든 데이터 지우기: 신고 설정과 보낸 표시를 처음 상태로 (세대는 그대로 두어 옛 신고가 되살아나지 않게) */
    suspend fun clearFalsePositiveState() {
        store.edit {
            it.remove(FALSE_POSITIVE_FEEDBACK)
            it.remove(REPORTED_FALSE_POSITIVES)
        }
    }

    /** 처음 맡기는 것이면 true (적어 둔다) */
    suspend fun markFalsePositiveReported(id: String): Boolean {
        var added = false
        store.edit {
            val now = it[REPORTED_FALSE_POSITIVES] ?: emptySet()
            if (id !in now) {
                added = true
                it[REPORTED_FALSE_POSITIVES] = (now + id).toList().takeLast(REPORTED_MAX).toSet()
            }
        }
        return added
    }

    /**
     * (보호자) 상대 폰 표시 이름: 연결 ID → 보호자가 고른 이름 (ux-flows §2). 이 폰에만 두고 서버로 보내지 않는다.
     * 키마다 하나: "phoneName." + 연결 ID
     */
    val phoneNames: Flow<Map<String, String>> = store.data.map { prefs ->
        prefs.asMap().mapNotNull { (key, value) ->
            val linkId = key.name.removePrefix(PHONE_NAME_PREFIX).takeIf { key.name.startsWith(PHONE_NAME_PREFIX) }
            (value as? String)?.let { name -> linkId?.let { it to name } }
        }.toMap()
    }

    /** [name]이 null이거나 비었으면 지운다 */
    suspend fun setPhoneName(linkId: String, name: String?) {
        val key = stringPreferencesKey(PHONE_NAME_PREFIX + linkId)
        store.edit { if (name.isNullOrBlank()) it.remove(key) else it[key] = name.trim() }
    }

    /** 모든 데이터 지우기: 표시 이름도 이 폰의 데이터다 */
    suspend fun clearPhoneNames() {
        store.edit { prefs ->
            prefs.asMap().keys.filter { it.name.startsWith(PHONE_NAME_PREFIX) }.forEach { prefs.remove(it) }
        }
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
        const val PHONE_NAME_PREFIX = "phoneName."
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
        val FALSE_POSITIVE_FEEDBACK = booleanPreferencesKey("falsePositiveFeedback")
        val REPORTED_FALSE_POSITIVES = stringSetPreferencesKey("reportedFalsePositives")
        val FALSE_POSITIVE_GENERATION = longPreferencesKey("falsePositiveGeneration")
        const val REPORTED_MAX = 500
    }
}

/** [role]은 core:model DeviceRole의 이름 (datastore는 model에 의존하지 않는다) */
data class RegisteredDevice(val deviceId: String, val role: String)
