package io.github.inq_o.phonedoctor.core.data

import io.github.inq_o.phonedoctor.core.datastore.UserPreferences
import io.github.inq_o.phonedoctor.core.model.DeviceRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

interface UserSettingsRepository {
    val onboardingDone: Flow<Boolean>

    /** 첫 실행에서 고른 쓰임새 (O1 "이 폰을 지켜요" / "가족 폰을 지켜요"). 고르기 전이면 null. 역할 선택이 없던 판에서 첫 설정을 끝낸 기기도 null(= 시니어) */
    val chosenRole: Flow<DeviceRole?>

    /** 공개 고지에 동의하고 첫 설정을 끝냄 */
    suspend fun completeOnboarding(disclosureVersion: Int)

    suspend fun chooseRole(role: DeviceRole)
}

class DefaultUserSettingsRepository @Inject constructor(
    private val preferences: UserPreferences,
) : UserSettingsRepository {
    override val onboardingDone: Flow<Boolean> = preferences.onboardingDone

    override val chosenRole: Flow<DeviceRole?> = preferences.chosenRole.map { name -> DeviceRole.entries.firstOrNull { it.name == name } }

    override suspend fun completeOnboarding(disclosureVersion: Int) {
        preferences.setDisclosureAcceptedVersion(disclosureVersion)
        preferences.setOnboardingDone(true)
    }

    override suspend fun chooseRole(role: DeviceRole) = preferences.setChosenRole(role.name)
}
