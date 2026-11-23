package io.github.inq_o.phonedoctor.ui

import io.github.inq_o.phonedoctor.core.model.DeviceRole
import io.github.inq_o.phonedoctor.core.model.DeviceRole.GUARDIAN
import io.github.inq_o.phonedoctor.core.model.DeviceRole.SENIOR
import org.junit.Assert.assertEquals
import org.junit.Test

class StartScreenTest {
    private fun start(done: Boolean, chosen: DeviceRole?, registered: DeviceRole? = null, family: Boolean = true, signedIn: Boolean = false) =
        startScreen(done, chosen, registered, family, signedIn)

    @Test
    fun releaseBuildWithoutFamilyIsAlwaysSenior() {
        assertEquals(StartScreen.SENIOR_ONBOARDING, start(false, null, family = false))
        assertEquals(StartScreen.SENIOR_HOME, start(true, null, family = false))
        // 보호자를 고른 뒤 가족 연결이 꺼진 빌드로 바뀌어도 시니어 첫 설정부터
        assertEquals(StartScreen.SENIOR_ONBOARDING, start(false, GUARDIAN, family = false, signedIn = true))
        assertEquals(StartScreen.SENIOR_ONBOARDING, start(false, GUARDIAN, registered = GUARDIAN, family = false))
    }

    @Test
    fun firstRunWithFamilyAsksRole() {
        assertEquals(StartScreen.ROLE_CHOICE, start(false, null))
        assertEquals(StartScreen.SENIOR_ONBOARDING, start(false, SENIOR))
    }

    @Test
    fun seniorWhoFinishedBeforeRoleChoiceExistedStaysSenior() {
        assertEquals(StartScreen.SENIOR_HOME, start(true, null))
        assertEquals(StartScreen.SENIOR_HOME, start(true, SENIOR, registered = SENIOR))
    }

    @Test
    fun guardianStartsAtSignInUntilSignedIn() {
        assertEquals(StartScreen.GUARDIAN_SIGN_IN, start(false, GUARDIAN))
        assertEquals(StartScreen.GUARDIAN_HOME, start(false, GUARDIAN, signedIn = true))
    }

    @Test
    fun registeredRoleWinsOverChoice() {
        // 서버에 보호자로 등록한 뒤 로그인이 풀리면 역할을 다시 고르지 않고 로그인만
        assertEquals(StartScreen.GUARDIAN_SIGN_IN_AGAIN, start(false, GUARDIAN, registered = GUARDIAN))
        assertEquals(StartScreen.GUARDIAN_SIGN_IN_AGAIN, start(false, SENIOR, registered = GUARDIAN))
        assertEquals(StartScreen.SENIOR_HOME, start(true, GUARDIAN, registered = SENIOR))
    }
}
