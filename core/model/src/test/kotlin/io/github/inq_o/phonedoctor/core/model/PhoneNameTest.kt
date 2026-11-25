package io.github.inq_o.phonedoctor.core.model

import org.junit.Test
import org.junit.Assert.assertEquals

class PhoneNameTest {
    @Test
    fun `보호자가 고른 이름이 먼저`() {
        assertEquals(PhoneName.Chosen("엄마"), PhoneName.of(" 엄마 ", "김영희"))
    }

    @Test
    fun `고르지 않았으면 상대가 정한 이름`() {
        assertEquals(PhoneName.Own("김영희"), PhoneName.of(null, "김영희"))
        assertEquals(PhoneName.Own("김영희"), PhoneName.of("  ", "김영희"))
    }

    @Test
    fun `둘 다 없으면 이름 없음 (역할 이름을 대신 넣지 않음)`() {
        assertEquals(PhoneName.Unnamed, PhoneName.of(null, ""))
        assertEquals(PhoneName.Unnamed, PhoneName.of("", null))
    }
}
