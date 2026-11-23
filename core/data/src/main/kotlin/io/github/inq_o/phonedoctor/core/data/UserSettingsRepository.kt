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

/**
 * 이 폰이 보호자 폰인지. 서버에 등록한 역할이 있으면 그것이 우선이다 (등록한 역할은 모든 데이터 지우기 전에는 바뀌지 않음).
 * 가족 연결이 꺼진 빌드는 보호자 화면을 쓸 수 없으므로 언제나 시니어 폰. 시작 화면과 주기 검사가 같은 기준을 쓴다
 */
fun isGuardianPhone(chosenRole: DeviceRole?, registeredRole: DeviceRole?, familyAvailable: Boolean): Boolean =
    familyAvailable && (registeredRole ?: chosenRole) == DeviceRole.GUARDIAN
