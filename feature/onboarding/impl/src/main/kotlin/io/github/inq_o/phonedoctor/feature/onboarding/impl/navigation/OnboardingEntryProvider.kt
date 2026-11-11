package io.github.inq_o.phonedoctor.feature.onboarding.impl.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import io.github.inq_o.phonedoctor.feature.onboarding.api.navigation.OnboardingNavKey
import io.github.inq_o.phonedoctor.feature.onboarding.impl.OnboardingScreen

fun EntryProviderScope<NavKey>.onboardingEntry(onFinished: () -> Unit) {
    entry<OnboardingNavKey> {
        OnboardingScreen(onFinished = onFinished)
    }
}
