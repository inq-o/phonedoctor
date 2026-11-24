package io.github.inq_o.phonedoctor.core.classifier.feature

/**
 * Python 3 코덱과 같은 결과를 내는 디코더 (PC 추출기와 비트 단위로 맞추려고).
 * JVM·ART 디코더는 잘못된 바이트를 대체 문자로 바꾸는 개수가 Python과 다르다 (예: `ED A0 80`이 Python은 U+FFFD 셋, JDK는 하나)
 */
internal object PyCodecs {
    private const val REPLACEMENT = '\uFFFD'

    /** `bytes.decode('utf-8', 'replace' | 'ignore')`: 잘못된 부분마다 가장 긴 유효 접두 단위로 하나씩 (Unicode 권고, CPython과 같음) */
    fun utf8(b: ByteArray, off: Int, len: Int, replace: Boolean): String {
        val sb = StringBuilder(len)
        var i = off
        val end = off + len
        fun bad() {
            if (replace) sb.append(REPLACEMENT)
        }
        while (i < end) {
            val c = b[i].toInt() and 0xFF
            if (c < 0x80) {
                sb.append(c.toChar())
                i++
                continue
            }
            val (need, lo, hi) = when (c) {
                in 0xC2..0xDF -> Triple(1, 0x80, 0xBF)
                0xE0 -> Triple(2, 0xA0, 0xBF)
                in 0xE1..0xEC, 0xEE, 0xEF -> Triple(2, 0x80, 0xBF)
                0xED -> Triple(2, 0x80, 0x9F)
                0xF0 -> Triple(3, 0x90, 0xBF)
                in 0xF1..0xF3 -> Triple(3, 0x80, 0xBF)
                0xF4 -> Triple(3, 0x80, 0x8F)
                else -> {
                    bad()
                    i++
                    continue
                }
            }
            var cp = c and (0x3F shr need)
            var j = i + 1
            var ok = true
            for (k in 0 until need) {
                if (j >= end) {
                    ok = false
                    break
                }
                val d = b[j].toInt() and 0xFF
                val (l, h) = if (k == 0) lo to hi else 0x80 to 0xBF
                if (d < l || d > h) {
                    ok = false
                    break
                }
                cp = (cp shl 6) or (d and 0x3F)
                j++
            }
            if (!ok) {
                // 유효했던 앞부분까지를 한 덩어리로 대체하고, 어긋난 바이트부터 다시 읽는다
                bad()
                i = j
                continue
            }
            sb.appendCodePoint(cp)
            i = j
        }
        return sb.toString()
    }

    /**
     * `bytes.decode('utf-16', 'replace')`: 앞의 BOM을 보고 바이트 순서를 정한다(`FF FE`면 빼고 LE, `FE FF`면 빼고 BE, 없으면 LE).
     * 짝 없는 대리 문자는 그 한 단위만 U+FFFD로
     */
    fun utf16(b: ByteArray, off: Int, len: Int): String {
        var i = off
        val end = off + len - (len and 1)
        var bigEndian = false
        if (len >= 2) {
            val b0 = b[off].toInt() and 0xFF
            val b1 = b[off + 1].toInt() and 0xFF
            if (b0 == 0xFF && b1 == 0xFE) {
                i += 2
            } else if (b0 == 0xFE && b1 == 0xFF) {
                i += 2
                bigEndian = true
            }
        }
        fun unit(p: Int): Int {
            val x = b[p].toInt() and 0xFF
            val y = b[p + 1].toInt() and 0xFF
            return if (bigEndian) (x shl 8) or y else x or (y shl 8)
        }
        val sb = StringBuilder(len / 2)
        while (i < end) {
            val u = unit(i)
            when {
                u in 0xD800..0xDBFF && i + 2 < end && unit(i + 2) in 0xDC00..0xDFFF -> {
                    sb.append(u.toChar()).append(unit(i + 2).toChar())
                    i += 4
                }
                u in 0xD800..0xDFFF -> {
                    sb.append(REPLACEMENT)
                    i += 2
                }
                else -> {
                    sb.append(u.toChar())
                    i += 2
                }
            }
        }
        // 홀수 바이트가 남으면 Python도 대체 문자 하나
        if (len and 1 == 1) sb.append(REPLACEMENT)
        return sb.toString()
    }
}
