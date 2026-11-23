package io.github.inq_o.phonedoctor.ui

import io.github.inq_o.phonedoctor.feature.guardian.api.navigation.GuardianHomeNavKey
import io.github.inq_o.phonedoctor.feature.guardian.api.navigation.GuardianSignInNavKey
import io.github.inq_o.phonedoctor.feature.onboarding.api.navigation.OnboardingNavKey
import io.github.inq_o.phonedoctor.feature.onboarding.api.navigation.RoleChoiceNavKey
import io.github.inq_o.phonedoctor.feature.seniorappdetail.api.navigation.AppDetailNavKey
import io.github.inq_o.phonedoctor.feature.seniorfamily.api.navigation.FamilyLinksNavKey
import io.github.inq_o.phonedoctor.feature.seniorfamily.api.navigation.LinkConfirmNavKey
import io.github.inq_o.phonedoctor.feature.seniorhome.api.navigation.SeniorHomeNavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationStackTest {
    @Test
    fun onboardingAndSignInSitOnRoleChoiceOnlyWhenItExists() {
        assertEquals(listOf(OnboardingNavKey), startStack(StartScreen.SENIOR_ONBOARDING, familyAvailable = false))
        assertEquals(listOf(RoleChoiceNavKey, OnboardingNavKey), startStack(StartScreen.SENIOR_ONBOARDING, familyAvailable = true))
        assertEquals(listOf(RoleChoiceNavKey, GuardianSignInNavKey), startStack(StartScreen.GUARDIAN_SIGN_IN, familyAvailable = true))
        assertEquals(listOf(GuardianHomeNavKey), startStack(StartScreen.GUARDIAN_HOME, familyAvailable = true))
    }

    @Test
    fun seniorNotificationsOpenAboveSeniorHome() {
        assertEquals(listOf(SeniorHomeNavKey, AppDetailNavKey(7)), pendingStack(SeniorHomeNavKey, PendingOpen.Alert(7)))
        assertEquals(listOf(SeniorHomeNavKey, LinkConfirmNavKey("l1")), pendingStack(SeniorHomeNavKey, PendingOpen.LinkRequest("l1")))
        assertEquals(listOf(SeniorHomeNavKey, FamilyLinksNavKey), pendingStack(SeniorHomeNavKey, PendingOpen.FamilyLinks))
    }

    @Test
    fun guardianLinkNotificationsOpenGuardianHomeAndDropSeniorOnes() {
        assertEquals(listOf(GuardianHomeNavKey), pendingStack(GuardianHomeNavKey, PendingOpen.FamilyLinks))
        assertEquals(emptyList<Any>(), pendingStack(GuardianHomeNavKey, PendingOpen.LinkRequest("l1")))
        assertEquals(emptyList<Any>(), pendingStack(GuardianHomeNavKey, PendingOpen.Alert(7)))
    }

    @Test
    fun waitsDuringOnboardingOrSignIn() {
        assertNull(pendingStack(RoleChoiceNavKey, PendingOpen.FamilyLinks))
        assertNull(pendingStack(OnboardingNavKey, PendingOpen.Alert(7)))
        assertNull(pendingStack(GuardianSignInNavKey, PendingOpen.FamilyLinks))
    }
}
