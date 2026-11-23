package io.github.inq_o.phonedoctor.core.classifier.feature

import org.junit.Test
import java.io.File
import java.util.zip.ZipFile
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 기기에서 남의 앱 매니페스트를 읽으므로, 깨진 파일은 앱을 죽이지 않고 ManifestException으로만 끝나야 한다 */
class AxmlRobustnessTest {
    private val manifest: ByteArray = run {
        val apk = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "core/classifier/src/test/resources/parity/a.apk") }.first { it.isFile }
        ZipFile(apk).use { z -> z.getInputStream(z.getEntry("AndroidManifest.xml")).readBytes() }
    }

    private fun readOrRejected(bytes: ByteArray) {
        try {
            ManifestReader.fromManifest(bytes, "", bytes.size.toLong())
        } catch (_: ManifestException) {
            // 거절은 괜찮다
        }
    }

    @Test
    fun intactManifestReads() {
        assertEquals("com.example.fixture.cleaner", ManifestReader.fromManifest(manifest, "", 0).packageName)
    }

    @Test
    fun truncatedManifestNeverCrashes() {
        for (n in 0 until manifest.size) readOrRejected(manifest.copyOf(n))
    }

    @Test
    fun corruptedManifestNeverCrashes() {
        val rnd = Random(20261124)
        repeat(3000) {
            val b = manifest.copyOf()
            repeat(1 + rnd.nextInt(8)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
            readOrRejected(b)
        }
    }

    @Test
    fun notAManifestIsRejected() {
        assertFailsWith<ManifestException> { ManifestReader.fromManifest("<manifest/>".toByteArray(), "", 0) }
    }
}
