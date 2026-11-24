package io.github.inq_o.phonedoctor.core.classifier.feature

import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 바이너리 XML(AndroidManifest.xml) 파서 (classifier.md §2).
 *
 * PC 추출기(androguard 4.1.4 `AXMLParser`·`AXMLPrinter`)와 같은 결과가 나와야 한다 (T024 일치 테스트).
 * 그래서 망가진 파일을 다루는 방식(모르는 조각 건너뛰기, 문자열 수 보정, 시스템 속성 ID로 이름 고치기, 이름·값 정리)도 그쪽을 따른다.
 * 남의 앱 파일을 기기에서 읽으므로 어떤 입력에도 앱을 죽이지 않는다: 재귀 없음, 문자열은 쓸 때만 풂, 실패는 [AxmlException]
 */
class XmlElement(
    /** 요소의 네임스페이스 URI (없으면 ""). androguard는 `{uri}이름`으로 다룬다 */
    val ns: String,
    val tag: String,
    /** 키: android 네임스페이스면 `android:이름`, 없으면 `이름`. 다른 네임스페이스는 버림. 같은 속성이 또 나오면 뒤엣것 */
    val attrs: Map<String, String>,
) {
    val children: MutableList<XmlElement> = mutableListOf()

    /** androguard `tag.get(ns + attr) or tag.get(attr)`: android 쪽이 비었으면(None·"") 네임스페이스 없는 쪽 (그것은 ""여도 그대로) */
    fun value(attr: String): String? = attrs["android:$attr"]?.takeIf { it.isNotEmpty() } ?: attrs[attr]

    /** android 네임스페이스 속성만 (androguard `item.get(self._ns(...))`) */
    fun androidValue(attr: String): String? = attrs["android:$attr"]

    /** lxml `.//이름`: 자기 아래 모든 깊이에서 네임스페이스 없는 그 이름 (문서 순서). 깊은 문서에서도 스택을 쓰지 않는다 */
    fun descendants(name: String, androidToo: Boolean = false): List<XmlElement> {
        val out = mutableListOf<XmlElement>()
        val stack = ArrayDeque<XmlElement>()
        children.asReversed().forEach(stack::addLast)
        while (stack.isNotEmpty()) {
            val e = stack.removeLast()
            if (e.tag == name && (e.ns.isEmpty() || (androidToo && e.ns == Axml.ANDROID_NS))) out += e
            e.children.asReversed().forEach(stack::addLast)
        }
        return out
    }

    /** lxml `findall(이름)`: 바로 아래 자식 중 네임스페이스 없는 그 이름 */
    fun childrenNamed(name: String): List<XmlElement> = children.filter { it.tag == name && it.ns.isEmpty() }
}

class AxmlException(message: String) : Exception(message)

object Axml {
    private const val RES_STRING_POOL_TYPE = 0x0001
    private const val RES_XML_RESOURCE_MAP_TYPE = 0x0180
    private const val RES_XML_FIRST_CHUNK_TYPE = 0x0100
    private const val RES_XML_LAST_CHUNK_TYPE = 0x017f
    private const val START_NAMESPACE = 0x0100
    private const val END_NAMESPACE = 0x0101
    private const val START_ELEMENT = 0x0102
    private const val END_ELEMENT = 0x0103
    private const val CDATA = 0x0104
    private const val UTF8_FLAG = 1 shl 8
    private const val NO_INDEX = 0xFFFFFFFFL
    const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    /**
     * 우리가 읽는 시스템 속성의 리소스 ID (android.R.attr). 패커가 문자열 이름을 바꿔도 시스템과 androguard는 ID로 읽는다.
     * androguard 표(0x01010000~0x0101056d 중 [SYSTEM_GAPS]를 뺀 1360개)에 있는 다른 ID는 우리 속성과 섞이지 않게 버린다
     */
    private val SYSTEM_ATTRS = mapOf(
        0x01010001L to "label", 0x01010003L to "name", 0x0101000eL to "enabled",
        0x0101020cL to "minSdkVersion", 0x01010202L to "targetActivity", 0x01010270L to "targetSdkVersion",
        0x01010010L to "exported", 0x01010018L to "authorities", 0x0101021bL to "versionCode", 0x0101021cL to "versionName",
    )
    private const val LAST_KNOWN_SYSTEM_ATTR = 0x0101056dL

    /** androguard 4.1.4 public 표에 없는 ID: 이 ID는 문자열 이름을 그대로 쓴다 */
    private val SYSTEM_GAPS: Set<Long> = buildSet {
        add(0x01010267L)
        add(0x010103a8L)
        add(0x010103c3L)
        for (id in 0x0101040eL..0x01010428L) add(id)
    }
    private const val DROP = "\u0000"

    /** 문서 뿌리 요소. 읽을 수 없는 파일이면 [AxmlException] */
    fun parse(bytes: ByteArray): XmlElement = try {
        parseOrThrow(bytes)
    } catch (e: AxmlException) {
        throw e
    } catch (e: RuntimeException) {
        // 깨진 파일이 어디서 어떻게 어긋나든 앱을 죽이지 않고 거절로 (파일 밖 읽기, 잘못된 위치 등)
        throw AxmlException("읽을 수 없는 구조: ${e.javaClass.simpleName}")
    }

    private fun parseOrThrow(bytes: ByteArray): XmlElement {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 8) throw AxmlException("8바이트보다 작음")
        val top = header(buf, 0)
        if (top.headerSize != 8) throw AxmlException("첫 조각 머리 크기가 8이 아님")
        if (top.size > bytes.size) throw AxmlException("선언한 크기가 파일보다 큼")
        val fileSize = top.size
        val poolHeader = header(buf, 8)
        if (poolHeader.type != RES_STRING_POOL_TYPE) throw AxmlException("문자열 표가 아님")
        if (poolHeader.headerSize != 0x1C) throw AxmlException("문자열 표 머리 크기가 28이 아님")
        val pool = StringPool(buf, poolHeader.start, poolHeader.size)

        // androguard: 문자열 표 다음은 (첫 머리 크기 + 문자열 표 크기)에서 시작
        var pos = top.headerSize + poolHeader.size
        val resourceIds = mutableListOf<Long>()
        val namespaces = mutableListOf<Pair<Long, Long>>()
        var root: XmlElement? = null
        val stack = ArrayDeque<XmlElement>()
        loop@ while (pos != fileSize) {
            // 머리를 읽지 못하면 무효 (androguard도 거기서 멈춤)
            val h = header(buf, pos)
            if (h.type == RES_XML_RESOURCE_MAP_TYPE) {
                if (h.size < 8 || h.size % 4 != 0) throw AxmlException("리소스 표 크기가 잘못됨")
                val n = (h.size - h.headerSize) / 4
                for (i in 0 until n) resourceIds += u32(buf, h.start + 8 + i * 4)
                // androguard는 다 읽은 자리에서 이어 간다 (조각 끝으로 건너뛰지 않음)
                pos = h.start + 8 + n * 4
                continue
            }
            if (h.type < RES_XML_FIRST_CHUNK_TYPE || h.type > RES_XML_LAST_CHUNK_TYPE || h.headerSize != 0x10) {
                pos = h.end
                continue
            }
            val body = h.start + 16
            when (h.type) {
                START_NAMESPACE, END_NAMESPACE -> {
                    val p = u32(buf, body) to u32(buf, body + 4)
                    if (h.type == START_NAMESPACE) namespaces += p else namespaces.remove(p)
                    // androguard는 네임스페이스 조각을 읽은 자리(머리 16 + 8)에서 이어 간다
                    pos = h.start + 24
                    continue@loop
                }
                START_ELEMENT -> {
                    val nsMap = namespaces.toSet().mapNotNull { (p, u) ->
                        val prefix = pool[p]
                        val uri = pool[u]
                        if (prefix.isNotEmpty() && uri.isNotEmpty()) prefix to uri.trim() else null
                    }.toMap()
                    val name = pool[u32(buf, body + 4)]
                    val attrSize = u16(buf, body + 10)
                    val count = (u32(buf, body + 12) and 0xFFFF).toInt()
                    val attrs = LinkedHashMap<String, String>()
                    var a = body + 20
                    repeat(count) {
                        val nsIdx = u32(buf, a)
                        val nameIdx = u32(buf, a + 4)
                        val rawIdx = u32(buf, a + 8)
                        val type = u8(buf, a + 15)
                        val data = u32(buf, a + 16)
                        val attrName = attributeName(pool, resourceIds, nameIdx)
                        if (attrName != DROP) {
                            val (uri, fixed) = fixName(if (nsIdx == NO_INDEX) "" else pool[nsIdx], attrName, nsMap)
                            val key = when (uri) {
                                ANDROID_NS -> "android:$fixed"
                                "" -> fixed
                                else -> null
                            }
                            key?.let { attrs[it] = fixValue(format(type, data) { pool[rawIdx] }) }
                        }
                        a += 20 + (attrSize - 20).coerceAtLeast(0)
                    }
                    if (name.isNotEmpty()) {
                        val elNs = u32(buf, body).let { if (it == NO_INDEX) "" else pool[it] }
                        val (uri, fixed) = fixName(elNs, name, nsMap)
                        val el = XmlElement(uri, fixed, attrs)
                        if (root == null) {
                            root = el
                        } else {
                            // androguard: 붙일 곳이 없으면 읽기를 멈춘다
                            val parent = stack.lastOrNull() ?: break@loop
                            parent.children += el
                        }
                        stack.addLast(el)
                    }
                }
                END_ELEMENT -> if (stack.isNotEmpty() && pool[u32(buf, body + 4)].isNotEmpty()) stack.removeLast()
                CDATA -> Unit
            }
            pos = h.end
        }
        return root ?: throw AxmlException("요소가 없음")
    }

    private fun attributeName(pool: StringPool, ids: List<Long>, idx: Long): String {
        val s = pool[idx]
        if (idx < ids.size) {
            val id = ids[idx.toInt()]
            SYSTEM_ATTRS[id]?.let { return it }
            // androguard 표에 있는 다른 시스템 속성 (그쪽은 표 이름으로 바꾼다): 우리가 읽는 이름과 섞이지 않게 버림
            if (id in 0x01010000L..LAST_KNOWN_SYSTEM_ATTR && id !in SYSTEM_GAPS) return DROP
        }
        return s
    }

    /** androguard `_fix_name`: 첫 글자, 이름에 박힌 접두어(`android:x`), 허용 안 되는 문자를 고친다. (네임스페이스 URI, 이름) */
    private fun fixName(uri: String, rawName: String, nsMap: Map<String, String>): Pair<String, String> {
        if (rawName.isEmpty()) return uri to rawName
        var name = if (rawName[0].isLetter() || rawName[0] == '_') rawName else "_$rawName"
        var ns = uri
        if (name.startsWith("android:") && ns.isEmpty() && "android" in nsMap) {
            ns = nsMap.getValue("android")
            name = name.removePrefix("android:")
        } else if (':' in name && ns.isEmpty()) {
            val prefix = name.substringBefore(':')
            nsMap[prefix]?.let {
                ns = it
                name = name.substringAfter(':')
            }
        }
        return ns to name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    /** androguard `_fix_value`: 첫 NUL에서 자르고(aapt와 같음), XML에 못 쓰는 문자는 `_`로 */
    private fun fixValue(value: String): String {
        val cut = value.substringBefore('\u0000')
        // 고칠 것이 없으면 같은 문자열을 그대로 (같은 큰 값을 여러 속성이 가리켜도 사본을 만들지 않게)
        if (cut.none { it.code < 0x20 && it != '\t' && it != '\n' && it != '\r' || it == '\uFFFE' || it == '\uFFFF' || it.isSurrogate() }) return cut
        val sb = StringBuilder(cut.length)
        var i = 0
        while (i < cut.length) {
            val cp = cut.codePointAt(i)
            val ok = cp == 0x9 || cp == 0xA || cp == 0xD || cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF
            if (ok) sb.appendCodePoint(cp) else sb.append('_')
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private val RADIX_MULTS = doubleArrayOf(0.00390625, 3.051758e-005, 1.192093e-007, 4.656613e-010)
    private val DIMENSION_UNITS = listOf("px", "dip", "sp", "pt", "in", "mm")
    private val FRACTION_UNITS = listOf("%", "%p")

    /** androguard `format_value` (값 종류마다 같은 문자열) */
    private fun format(type: Int, data: Long, string: () -> String): String {
        fun pkg() = if (data shr 24 == 1L) "android:" else ""
        fun complex() = (data and 0xFFFFFF00L).toDouble() * RADIX_MULTS[((data shr 4) and 3).toInt()]
        return when (type) {
            0x03 -> string()
            0x02 -> "?" + pkg() + "%08X".format(data)
            0x01 -> "@" + pkg() + "%08X".format(data)
            0x04 -> pyF(java.lang.Float.intBitsToFloat(data.toInt()).toDouble())
            0x11 -> "0x%08X".format(data)
            0x12 -> if (data == 0L) "false" else "true"
            0x05 -> pyF(complex()) + (DIMENSION_UNITS.getOrNull((data and 0xF).toInt()) ?: throw AxmlException("모르는 치수 단위"))
            0x06 -> pyF(complex() * 100) + (FRACTION_UNITS.getOrNull((data and 0xF).toInt()) ?: throw AxmlException("모르는 비율 단위"))
            in 0x1c..0x1f -> "#%08X".format(data)
            in 0x10..0x1f -> data.toInt().toString()
            else -> "<0x%X, type 0x%02X>".format(data, type)
        }
    }

    /** Python `"%f" % x`: 소수 6자리, 이진 값 그대로 짝수 쪽 반올림 */
    private fun pyF(x: Double): String {
        if (x.isNaN()) return "nan"
        if (x.isInfinite()) return if (x > 0) "inf" else "-inf"
        val s = BigDecimal(x).setScale(6, RoundingMode.HALF_EVEN).toPlainString()
        return if (x < 0 || (x == 0.0 && 1 / x < 0)) (if (s.startsWith("-")) s else "-$s") else s
    }

    /** [start]는 실제 머리 위치, [end]는 androguard처럼 처음 읽기 시작한 위치 + 크기 */
    private class Header(val type: Int, val headerSize: Int, val size: Int, val start: Int, val end: Int)

    /** androguard `ARSCHeader`: 조각 사이에 끼운 쓰레기 바이트는 한 바이트씩 건너뛴다 */
    private fun header(buf: ByteBuffer, origin: Int): Header {
        if (origin + 8 > buf.limit()) throw AxmlException("조각 머리를 읽을 수 없음 ($origin)")
        var cur = origin
        var type: Int
        var headerSize: Int
        var size: Long
        while (true) {
            if (cur + 8 > buf.limit()) throw AxmlException("조각 머리를 읽을 수 없음 ($cur)")
            type = u16(buf, cur)
            headerSize = u16(buf, cur + 2)
            size = u32(buf, cur + 4)
            // 패커가 끝 네임스페이스 조각 크기를 0으로 둔 경우
            if (size < 8 && buf.limit() == cur + headerSize + 8) size = 24
            val ok = headerSize >= 8 && size >= headerSize
            if ((type < RES_XML_FIRST_CHUNK_TYPE || type > RES_XML_LAST_CHUNK_TYPE) && ok) break
            if (cur == 0 || ok) break
            cur += 1
        }
        if (headerSize < 8 || size < 8 || size < headerSize) throw AxmlException("조각 크기가 잘못됨 ($cur)")
        if (size > Int.MAX_VALUE || origin + size > Int.MAX_VALUE) throw AxmlException("조각이 너무 큼")
        return Header(type, headerSize, size.toInt(), cur, origin + size.toInt())
    }

    private fun u8(buf: ByteBuffer, pos: Int) = buf.get(pos).toInt() and 0xFF
    private fun u16(buf: ByteBuffer, pos: Int) = buf.getShort(pos).toInt() and 0xFFFF
    private fun u32(buf: ByteBuffer, pos: Int) = buf.getInt(pos).toLong() and 0xFFFFFFFFL

    /** androguard `StringBlock`. 문자열은 쓸 때 풀고 기억한다 (같은 큰 문자열을 수만 번 가리키는 표로 메모리를 다 쓰지 않게) */
    private class StringPool(private val buf: ByteBuffer, private val start: Int, chunkSize: Int) {
        private val count: Int
        private val utf8: Boolean
        private val chars: ByteArray
        /** 위치(문자열 데이터 안 오프셋)마다 한 번만 푼다: 여러 번호가 같은 위치를 가리켜도 사본이 하나 */
        private val cache = HashMap<Int, String>()

        init {
            var n = u32(buf, start + 8).toInt()
            val styleCount = u32(buf, start + 12).toInt()
            utf8 = (u32(buf, start + 16).toInt() and UTF8_FLAG) != 0
            val stringsOffset = u32(buf, start + 20).toInt()
            val stylesOffset = u32(buf, start + 24).toInt()
            // androguard: 문자열 수와 시작 위치가 맞지 않으면 시작 위치를 믿는다
            val fromOffset = stringsOffset - (styleCount * 4 + 28)
            if (fromOffset / 4 != n || fromOffset % 4 != 0) n = fromOffset / 4
            // 위치 표가 파일 안에 있어야 한다 (깨진 수로 큰 배열을 만들지 않게)
            val room = (buf.limit() - start - 28) / 4
            if (n < 0 || styleCount < 0 || n > room || styleCount > room - n) throw AxmlException("문자열 수가 파일보다 큼")
            count = n
            val charsSize = if (stylesOffset != 0 && styleCount != 0) stylesOffset - stringsOffset else chunkSize - stringsOffset
            // androguard는 위치 표 바로 뒤부터 차례로 읽는다 (보통 stringsOffset과 같은 자리)
            val charsStart = start + 28 + count * 4 + styleCount * 4
            chars = ByteArray(charsSize.coerceIn(0, (buf.limit() - charsStart).coerceAtLeast(0)))
            val view = buf.duplicate()
            view.position(charsStart)
            view.get(chars)
        }

        operator fun get(idx: Long): String {
            if (idx < 0 || idx >= count) return ""
            val i = idx.toInt()
            val off = u32(buf, start + 28 + i * 4).toInt()
            return cache.getOrPut(off) {
                try {
                    if (utf8) decode8(off) else decode16(off)
                } catch (e: AxmlException) {
                    throw e
                } catch (_: IndexOutOfBoundsException) {
                    ""
                }
            }
        }

        private fun length(off: Int, wide: Boolean): Pair<Int, Int> = if (!wide) {
            val a = chars[off].toInt() and 0xFF
            if (a and 0x80 != 0) (((a and 0x7F) shl 8) or (chars[off + 1].toInt() and 0xFF)) to 2 else a to 1
        } else {
            val a = (chars[off].toInt() and 0xFF) or ((chars[off + 1].toInt() and 0xFF) shl 8)
            if (a and 0x8000 != 0) {
                val b = (chars[off + 2].toInt() and 0xFF) or ((chars[off + 3].toInt() and 0xFF) shl 8)
                (((a and 0x7FFF) shl 16) or b) to 4
            } else {
                a to 2
            }
        }

        private fun decode8(start: Int): String {
            var off = start + length(start, false).second
            val (bytes, skip) = length(off, false)
            off += skip
            if (chars.size < off + bytes) return ""
            if (chars[off + bytes].toInt() != 0) return ""
            return PyCodecs.utf8(chars, off, bytes, replace = true)
        }

        private fun decode16(start: Int): String {
            val (len, skip) = length(start, true)
            val off = start + skip
            if (len < 0 || chars.size < off + len.toLong() * 2) return ""
            // androguard는 끝 표시가 없으면 예외(파일 전체 거절)
            if (chars[off + len * 2].toInt() != 0 || chars[off + len * 2 + 1].toInt() != 0) throw AxmlException("UTF-16 끝 표시 없음")
            return PyCodecs.utf16(chars, off, len * 2)
        }
    }
}
