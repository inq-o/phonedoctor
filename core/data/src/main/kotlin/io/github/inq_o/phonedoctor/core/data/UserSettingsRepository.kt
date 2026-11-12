package io.github.inq_o.phonedoctor.core.data

import io.github.inq_o.phonedoctor.core.datastore.UserPreferences
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

interface UserSettingsRepository {
    val onboardingDone: Flow<Boolean>

    /** 공개 고지에 동의하고 첫 설정을 끝냄 */
    suspend fun completeOnboarding(disclosureVersion: Int)
}

class DefaultUserSettingsRepository @Inject constructor(
    private val preferences: UserPreferences,
) : UserSettingsRepository {
    override val onboardingDone: Flow<Boolean> = preferences.onboardingDone

    override suspend fun completeOnboarding(disclosureVersion: Int) {
        preferences.setDisclosureAcceptedVersion(disclosureVersion)
        preferences.setOnboardingDone(true)
    }
}
