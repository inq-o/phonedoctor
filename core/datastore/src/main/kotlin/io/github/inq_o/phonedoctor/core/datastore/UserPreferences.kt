package io.github.inq_o.phonedoctor.core.datastore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** 기기 설정 (data-model.md §5). 지금은 온보딩·고지 동의만 */
class UserPreferences @Inject constructor(private val store: DataStore<Preferences>) {
    val onboardingDone: Flow<Boolean> = store.data.map { it[ONBOARDING_DONE] ?: false }

    /** 동의한 고지 문구 판. 문구가 바뀌면 다시 받는다 */
    val disclosureAcceptedVersion: Flow<Int> = store.data.map { it[DISCLOSURE_VERSION] ?: 0 }

    suspend fun setOnboardingDone(done: Boolean) {
        store.edit { it[ONBOARDING_DONE] = done }
    }

    suspend fun setDisclosureAcceptedVersion(version: Int) {
        store.edit { it[DISCLOSURE_VERSION] = version }
    }

    private companion object {
        val ONBOARDING_DONE = booleanPreferencesKey("onboardingDone")
        val DISCLOSURE_VERSION = intPreferencesKey("disclosureAcceptedVersion")
    }
}
