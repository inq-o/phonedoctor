package io.github.inq_o.phonedoctor.feature.onboarding.impl.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import io.github.inq_o.phonedoctor.feature.onboarding.api.navigation.OnboardingNavKey
import io.github.inq_o.phonedoctor.feature.onboarding.impl.OnboardingScreen

/** [onFinished]의 인자: 마지막 단계에서 가족 연결을 시작하기로 했는지 */
fun EntryProviderScope<NavKey>.onboardingEntry(onFinished: (connectFamily: Boolean) -> Unit) {
    entry<OnboardingNavKey> {
        OnboardingScreen(onFinished = onFinished)
    }
}
