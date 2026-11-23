package io.github.inq_o.phonedoctor.core.classifier.feature

import org.junit.Test
import kotlin.test.assertEquals

/** 기대값은 CPython 3.11 `bytes.decode` 결과 */
class PyCodecsTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun cps(s: String) = s.codePoints().toArray().toList()
    private fun u8(h: String, replace: Boolean = true) = cps(hex(h).let { PyCodecs.utf8(it, 0, it.size, replace) })
    private fun u16(h: String) = cps(hex(h).let { PyCodecs.utf16(it, 0, it.size) })

    @Test
    fun utf8LikePython() {
        assertEquals(listOf(0xFFFD, 0xFFFD, 0xFFFD), u8("eda080"))
        assertEquals(listOf(0xFFFD, 0x41), u8("e18041"))
        assertEquals(listOf(0xFFFD), u8("f09080"))
        assertEquals(listOf(0xFFFD, 0xFFFD), u8("c0af"))
        assertEquals(listOf(0x41, 0xFFFD, 0x42), u8("41ff42"))
        assertEquals(listOf(0xFFFD, 0xFFFD, 0xFFFD, 0xFFFD), u8("f4908080"))
        assertEquals(listOf(0x20AC), u8("e282ac"))
        assertEquals(listOf(0x41, 0x42), u8("41ff42", replace = false))
        assertEquals(listOf(0x41), u8("e18041", replace = false))
    }

    @Test
    fun utf16LikePython() {
        assertEquals(listOf(0xFFFD, 0x41), u16("00d84100"))
        assertEquals(listOf(0x41), u16("fffe4100"))
        assertEquals(listOf(0x41), u16("feff0041"))
        assertEquals(listOf(0xFFFD), u16("00dc"))
        assertEquals(listOf(0x1F600), u16("3dd800de"))
    }
}
