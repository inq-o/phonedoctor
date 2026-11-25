package io.github.inq_o.phonedoctor.feature.guardian.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneNameUiTest {
    @Test
    fun `보호자가 둘이면 요청의 연결 목록 중 이 폰에 이름이 있는 연결로 찾는다`() {
        // 서버는 그 요청을 받은 모든 보호자의 연결을 정렬해 준다: 첫 ID가 다른 보호자의 것일 수 있다
        val mine = mapOf("l2" to "엄마")
        assertEquals("엄마", chosenNameFor(listOf("l1", "l2"), mine))
        assertNull(chosenNameFor(listOf("l1"), mine))
        assertNull(chosenNameFor(emptyList(), mine))
    }

    @Test
    fun `직접 입력 끝의 폰은 떼고, 비면 저장할 것이 없다`() {
        assertEquals("엄마", PhoneNameChoice(custom = true, text = " 엄마 폰 ").value)
        assertEquals("큰이모", PhoneNameChoice(custom = true, text = "큰이모").value)
        assertNull(PhoneNameChoice(custom = true, text = " 폰 ").value)
        assertNull(PhoneNameChoice().value)
        assertEquals("아빠", PhoneNameChoice(preset = "아빠").value)
    }
}
