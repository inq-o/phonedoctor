package io.github.inq_o.phonedoctor.core.data

import io.github.inq_o.phonedoctor.core.model.DeviceRole.GUARDIAN
import io.github.inq_o.phonedoctor.core.model.DeviceRole.SENIOR
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardianPhoneTest {
    @Test
    fun guardianOnlyWhenFamilyIsAvailable() {
        assertTrue(isGuardianPhone(GUARDIAN, null, familyAvailable = true))
        // 가족 연결이 꺼진 빌드는 시니어로 첫 설정을 하므로 검사도 돌아야 한다
        assertFalse(isGuardianPhone(GUARDIAN, null, familyAvailable = false))
        assertFalse(isGuardianPhone(GUARDIAN, GUARDIAN, familyAvailable = false))
        assertFalse(isGuardianPhone(null, null, familyAvailable = true))
    }

    @Test
    fun registeredRoleWins() {
        assertTrue(isGuardianPhone(SENIOR, GUARDIAN, familyAvailable = true))
        assertFalse(isGuardianPhone(GUARDIAN, SENIOR, familyAvailable = true))
    }
}
