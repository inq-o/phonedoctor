package io.github.inq_o.phonedoctor.ui

import io.github.inq_o.phonedoctor.core.model.DeviceRole
import org.junit.Assert.assertEquals
import org.junit.Test

class StartScreenTest {
    @Test
    fun releaseBuildWithoutFamilyIsAlwaysSenior() {
        assertEquals(StartScreen.SENIOR_ONBOARDING, startScreen(false, null, familyAvailable = false, guardianSignedIn = false))
        assertEquals(StartScreen.SENIOR_HOME, startScreen(true, null, familyAvailable = false, guardianSignedIn = false))
        // 보호자를 고른 뒤 가족 연결이 꺼진 빌드로 바뀌어도 시니어 첫 설정부터
        assertEquals(StartScreen.SENIOR_ONBOARDING, startScreen(false, DeviceRole.GUARDIAN, familyAvailable = false, guardianSignedIn = true))
    }

    @Test
    fun firstRunWithFamilyAsksRole() {
        assertEquals(StartScreen.ROLE_CHOICE, startScreen(false, null, familyAvailable = true, guardianSignedIn = false))
        assertEquals(StartScreen.SENIOR_ONBOARDING, startScreen(false, DeviceRole.SENIOR, familyAvailable = true, guardianSignedIn = false))
    }

    @Test
    fun seniorWhoFinishedBeforeRoleChoiceExistedStaysSenior() {
        assertEquals(StartScreen.SENIOR_HOME, startScreen(true, null, familyAvailable = true, guardianSignedIn = false))
        assertEquals(StartScreen.SENIOR_HOME, startScreen(true, DeviceRole.SENIOR, familyAvailable = true, guardianSignedIn = false))
    }

    @Test
    fun guardianStartsAtSignInUntilSignedIn() {
        assertEquals(StartScreen.GUARDIAN_SIGN_IN, startScreen(false, DeviceRole.GUARDIAN, familyAvailable = true, guardianSignedIn = false))
        assertEquals(StartScreen.GUARDIAN_HOME, startScreen(false, DeviceRole.GUARDIAN, familyAvailable = true, guardianSignedIn = true))
    }
}
