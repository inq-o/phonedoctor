package io.github.inq_o.phonedoctor.core.classifier.feature

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * APK에서 파일 하나만 꺼내는 ZIP 읽기 (PC 쪽 androguard 4.1.4가 쓰는 apkInspector와 같은 방식).
 *
 * `java.util.zip.ZipFile`을 쓰지 않는 이유: 악성 앱이 압축 방식 값을 조작하면(예: 99) ZipFile은 예외를 내지만
 * 설치기와 PC 추출기는 저장(STORED)으로 읽는다. Android 14+의 경로 검사(ZipPathValidator)에도 걸리지 않게 한다
 */
object ApkZip {
    /** 매니페스트가 이보다 크면 읽지 않는다 (압축 폭탄) */
    const val MAX_ENTRY = 8 * 1024 * 1024

    fun read(apk: File, name: String): ByteArray = RandomAccessFile(apk, "r").use { f ->
        val size = f.length()
        val eocd = findEocd(f, size) ?: throw ManifestException("ZIP 끝 표시(EOCD) 없음")
        f.seek(eocd + 16)
        var pos = u32(f).toLong()
        var entry: Entry? = null
        // 중앙 디렉터리: 서명이 끊길 때까지 (apkInspector와 같이 개수 필드는 믿지 않음). 같은 이름이면 뒤엣것
        while (pos + 46 <= size) {
            f.seek(pos)
            if (u32(f) != 0x02014b50) break
            f.seek(pos + 10)
            val method = u16(f)
            f.seek(pos + 20)
            val compressed = u32(f).toLong() and 0xFFFFFFFFL
            val uncompressed = u32(f).toLong() and 0xFFFFFFFFL
            val nameLen = u16(f)
            val extraLen = u16(f)
            val commentLen = u16(f)
            f.seek(pos + 42)
            val local = u32(f).toLong() and 0xFFFFFFFFL
            val nameBytes = ByteArray(nameLen).also { f.readFully(it) }
            if (decodeIgnoring(nameBytes) == name) entry = Entry(method, compressed, uncompressed, local)
            pos += 46L + nameLen + extraLen + commentLen
        }
        val e = entry ?: throw ManifestException("$name 없음")
        f.seek(e.local)
        if (u32(f) != 0x04034b50) throw ManifestException("로컬 헤더가 아님")
        f.seek(e.local + 8)
        val method = u16(f)
        f.seek(e.local + 18)
        var compressed = u32(f).toLong() and 0xFFFFFFFFL
        var uncompressed = u32(f).toLong() and 0xFFFFFFFFL
        val nameLen = u16(f)
        val extraLen = u16(f)
        if (compressed == 0L || uncompressed == 0L) {
            compressed = e.compressed
            uncompressed = e.uncompressed
        }
        val data = e.local + 30 + nameLen + extraLen
        fun bytes(n: Long): ByteArray {
            if (n > MAX_ENTRY) throw ManifestException("$name 이 너무 큼 ($n)")
            val take = minOf(n, (size - data).coerceAtLeast(0)).toInt()
            return ByteArray(take).also { f.seek(data); f.readFully(it) }
        }
        when {
            method == 0 -> bytes(uncompressed)
            method == 8 -> inflate(bytes(compressed), pure = false)
            compressed == uncompressed -> bytes(uncompressed)
            // 조작된 압축 방식: 깨끗한 deflate로 풀리면 그것, 아니면 저장으로 본다
            else -> inflateOrNull(bytes(compressed)) ?: bytes(uncompressed)
        }
    }

    private class Entry(val method: Int, val compressed: Long, val uncompressed: Long, val local: Long)

    private fun findEocd(f: RandomAccessFile, size: Long): Long? {
        // 뒤에서부터 찾는다 (EOCD를 여러 개 넣은 APK가 있음). 1KB씩, 4바이트 겹쳐서
        val chunk = 1024
        var offset = 0L
        while (offset < size) {
            val position = maxOf(0L, size - offset - chunk)
            val n = minOf(chunk.toLong(), size - position).toInt()
            val buf = ByteArray(n)
            f.seek(position)
            f.readFully(buf)
            for (i in n - 4 downTo 0) {
                if (buf[i] == 0x50.toByte() && buf[i + 1] == 0x4b.toByte() && buf[i + 2] == 0x05.toByte() && buf[i + 3] == 0x06.toByte()) {
                    return if (position + i + 22 <= size) position + i else null
                }
            }
            if (position == 0L) break
            offset += chunk - 4
        }
        return null
    }

    private fun inflate(input: ByteArray, pure: Boolean): ByteArray {
        val inf = Inflater(true)
        try {
            inf.setInput(input)
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                out.write(buf, 0, n)
                if (out.size() > MAX_ENTRY) throw ManifestException("풀어 보니 너무 큼")
            }
            if (pure && (!inf.finished() || inf.remaining > 0)) throw DataFormatException("깨끗한 deflate가 아님")
            if (!pure && !inf.finished()) throw ManifestException("deflate가 끝나지 않음")
            return out.toByteArray()
        } catch (e: DataFormatException) {
            if (pure) throw e
            throw ManifestException("deflate 오류: ${e.message}")
        } finally {
            inf.end()
        }
    }

    private fun inflateOrNull(input: ByteArray): ByteArray? = try {
        inflate(input, pure = true)
    } catch (_: DataFormatException) {
        null
    }

    /** Python `decode('utf-8', 'ignore')`: 잘못된 바이트는 버림 */
    private fun decodeIgnoring(b: ByteArray): String = PyCodecs.utf8(b, 0, b.size, replace = false)

    private fun u16(f: RandomAccessFile): Int = f.read() or (f.read() shl 8)
    private fun u32(f: RandomAccessFile): Int = u16(f) or (u16(f) shl 16)
}
