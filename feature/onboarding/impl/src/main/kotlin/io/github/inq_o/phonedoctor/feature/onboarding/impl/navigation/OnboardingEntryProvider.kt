package io.github.inq_o.phonedoctor.feature.onboarding.impl.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import io.github.inq_o.phonedoctor.core.model.DeviceRole
import io.github.inq_o.phonedoctor.feature.onboarding.api.navigation.OnboardingNavKey
import io.github.inq_o.phonedoctor.feature.onboarding.api.navigation.RoleChoiceNavKey
import io.github.inq_o.phonedoctor.feature.onboarding.impl.OnboardingScreen
import io.github.inq_o.phonedoctor.feature.onboarding.impl.RoleChoiceScreen

/**
 * @param onRoleChosen O1에서 고른 역할 (저장한 뒤)
 * @param onFinished 시니어 첫 설정을 끝냄. 인자는 마지막 단계에서 가족 연결을 시작하기로 했는지
 */
fun EntryProviderScope<NavKey>.onboardingEntry(
    onRoleChosen: (DeviceRole) -> Unit,
    onFinished: (connectFamily: Boolean) -> Unit,
) {
    entry<RoleChoiceNavKey> {
        RoleChoiceScreen(onChosen = onRoleChosen)
    }
    entry<OnboardingNavKey> {
        OnboardingScreen(onFinished = onFinished)
    }
}
