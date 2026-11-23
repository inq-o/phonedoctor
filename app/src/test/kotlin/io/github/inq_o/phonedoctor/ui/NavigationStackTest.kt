package io.github.inq_o.phonedoctor.ui

import io.github.inq_o.phonedoctor.feature.guardian.api.navigation.AlertDetailNavKey
import io.github.inq_o.phonedoctor.feature.guardian.api.navigation.GuardianHomeNavKey
import io.github.inq_o.phonedoctor.feature.guardian.api.navigation.GuardianSignInNavKey
import io.github.inq_o.phonedoctor.feature.onboarding.api.navigation.OnboardingNavKey
import io.github.inq_o.phonedoctor.feature.onboarding.api.navigation.RoleChoiceNavKey
import io.github.inq_o.phonedoctor.feature.seniorappdetail.api.navigation.AppDetailNavKey
import io.github.inq_o.phonedoctor.feature.seniorfamily.api.navigation.FamilyLinksNavKey
import io.github.inq_o.phonedoctor.feature.seniorfamily.api.navigation.LinkConfirmNavKey
import io.github.inq_o.phonedoctor.feature.seniorhome.api.navigation.SeniorHomeNavKey
import io.github.inq_o.phonedoctor.feature.seniorsites.api.navigation.SiteAlertNavKey
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
        // 보호자로 등록한 폰은 역할 선택으로 돌아가지 않는다
        assertEquals(listOf(GuardianSignInNavKey), startStack(StartScreen.GUARDIAN_SIGN_IN_AGAIN, familyAvailable = true))
    }

    @Test
    fun seniorNotificationsOpenAboveSeniorHome() {
        assertEquals(listOf(SeniorHomeNavKey, AppDetailNavKey(7)), pendingStack(listOf(SeniorHomeNavKey), PendingOpen.Alert(7)))
        assertEquals(listOf(SeniorHomeNavKey, LinkConfirmNavKey("l1")), pendingStack(listOf(SeniorHomeNavKey), PendingOpen.LinkRequest("l1")))
        assertEquals(listOf(SeniorHomeNavKey, FamilyLinksNavKey), pendingStack(listOf(SeniorHomeNavKey), PendingOpen.FamilyLinks))
        // 사이트 광고 알림은 그 사이트 설정을 바로 여는 화면으로
        assertEquals(
            listOf(SeniorHomeNavKey, SiteAlertNavKey("https://ads.example.com", openSettings = true)),
            pendingStack(listOf(SeniorHomeNavKey), PendingOpen.Site("https://ads.example.com")),
        )
        assertEquals(emptyList<Any>(), pendingStack(listOf(GuardianHomeNavKey), PendingOpen.Site("https://ads.example.com")))
    }

    @Test
    fun guardianLinkNotificationsOpenGuardianHomeAndDropSeniorOnes() {
        assertEquals(listOf(GuardianHomeNavKey), pendingStack(listOf(GuardianHomeNavKey), PendingOpen.FamilyLinks))
        assertEquals(emptyList<Any>(), pendingStack(listOf(GuardianHomeNavKey), PendingOpen.LinkRequest("l1")))
        assertEquals(emptyList<Any>(), pendingStack(listOf(GuardianHomeNavKey), PendingOpen.Alert(7)))
        // 요청 알림은 홈 위에 G2, 시니어 폰에는 오지 않는 알림이라 버린다
        assertEquals(listOf(GuardianHomeNavKey, AlertDetailNavKey("a1")), pendingStack(listOf(GuardianHomeNavKey), PendingOpen.GuardianRequest("a1")))
        assertEquals(emptyList<Any>(), pendingStack(listOf(SeniorHomeNavKey), PendingOpen.GuardianRequest("a1")))
    }

    @Test
    fun waitsDuringOnboardingOrSignIn() {
        assertNull(pendingStack(listOf(RoleChoiceNavKey), PendingOpen.FamilyLinks))
        assertNull(pendingStack(listOf(RoleChoiceNavKey, OnboardingNavKey), PendingOpen.Alert(7)))
        assertNull(pendingStack(listOf(GuardianSignInNavKey), PendingOpen.FamilyLinks))
        // 로그인이 풀려 홈 위에서 다시 로그인하는 중에도 닫지 않는다
        assertNull(pendingStack(listOf(GuardianHomeNavKey, GuardianSignInNavKey), PendingOpen.FamilyLinks))
    }
}
