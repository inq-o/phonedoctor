package io.github.inq_o.phonedoctor.appfunctions

import io.github.inq_o.phonedoctor.core.model.ScanResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneCheckTest {
    private fun run(result: ScanResult?, open: Int) = runBlocking { checkPhone({ result }, { open }) }

    @Test
    fun reportsOnlyTheCount() {
        val found = run(ScanResult.OK, 2)
        assertTrue(found.checked)
        assertEquals(2, found.appsToCheck)
        assertEquals("확인이 필요한 앱이 2개 있어요. 폰주치의 앱을 열어 확인해 주세요.", found.message)
        assertEquals("광고를 띄우는 앱을 찾지 못했어요.", run(ScanResult.OK, 0).message)
    }

    @Test
    fun explainsWhyItCouldNotCheck() {
        val noPermission = run(ScanResult.NO_PERMISSION, 1)
        assertFalse(noPermission.checked)
        assertTrue("사용 기록 접근" in noPermission.message)
        assertTrue("잠금" in run(ScanResult.LOCKED, 0).message)
        assertTrue("잠시 뒤" in run(ScanResult.ERROR, 0).message)
        // 보호자 폰은 검사하지 않는다
        val guardian = run(null, 3)
        assertFalse(guardian.checked)
        assertEquals(0, guardian.appsToCheck)
        assertTrue("보호자" in guardian.message)
    }
}
