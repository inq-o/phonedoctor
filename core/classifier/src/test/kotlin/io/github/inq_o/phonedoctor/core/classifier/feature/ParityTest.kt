package io.github.inq_o.phonedoctor.core.classifier.feature

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * 일치 테스트 (T024, classifier.md §5): 같은 APK에서 PC 추출기(androguard)와 기기 추출기의 벡터가 비트 단위로 같아야 한다.
 * 기대값은 `ml/extract/parity_expected.py`가 만든다. 라벨은 기기에선 PackageManager가 주므로 기대값의 라벨을 입력으로 쓴다
 */
class ParityTest {
    private val repo: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "ml/schema/feature_schema_v1.json").isFile } ?: error("레포 뿌리를 찾지 못함")
    private val schema = FeatureSchema.parse(File(repo, "ml/schema/feature_schema_v1.json").readText())

    /** 레포에 넣은 작은 APK (우리가 쓴 매니페스트) — CI에서 늘 돈다 */
    @Test
    fun fixturesMatchPcExtractor() {
        val dir = File(repo, "core/classifier/src/test/resources/parity")
        val checked = check(File(dir, "expected.jsonl"), dir)
        assertEquals(2, checked)
    }

    /** 손으로 만든 패커·깨진 모양 (ml/extract/parity_fixture/craft.py). PC가 거절한 것은 기기도 거절해야 한다 */
    @Test
    fun craftedManifestsMatchPcExtractor() {
        val dir = File(repo, "core/classifier/src/test/resources/parity/crafted")
        val checked = check(File(dir, "expected.jsonl"), dir)
        assertEquals(27, checked)
    }

    /** 받은 APK (ml/data, 레포 밖). 기대값 파일이 있을 때만 — 만드는 법은 parity_expected.py */
    @Test
    fun downloadedApksMatchPcExtractor() {
        val expected = File(repo, "ml/data/parity_expected.jsonl")
        assumeTrue("ml/data/parity_expected.jsonl 없음 (로컬 자료)", expected.isFile)
        val checked = check(expected, File(repo, "ml/data/apk"))
        assertTrue(checked >= 20, "20개 이상이어야 함 (classifier.md §5): $checked")
    }

    private fun check(expected: File, apkDir: File): Int {
        val problems = mutableListOf<String>()
        var n = 0
        expected.readLines().filter { it.isNotBlank() }.forEach { line ->
            val row = Json.parseToJsonElement(line).jsonObject
            val file = row.getValue("file").jsonPrimitive.content
            val apk = File(apkDir, file)
            if (!apk.isFile || sha256(apk) != row.getValue("sha256").jsonPrimitive.content) {
                problems += "$file: 기대값을 만든 APK와 다름 (없거나 SHA-256이 다름) — parity_expected.py로 다시 만들 것"
                return@forEach
            }
            if ("reject" in row) {
                try {
                    ManifestReader.read(File(apkDir, file), "")
                    problems += "$file: PC는 거절(${row.getValue("reject").jsonPrimitive.content})했는데 기기는 읽음"
                } catch (_: ManifestException) {
                    // 같다
                }
                n++
                return@forEach
            }
            assertEquals(schema.version, row.getValue("schema_version").jsonPrimitive.int)
            val label = row.getValue("label").jsonPrimitive.content
            val raw = try {
                ManifestReader.read(File(apkDir, file), label)
            } catch (e: ManifestException) {
                problems += "$file: 기기 쪽에서 읽지 못함 (${e.message})"
                return@forEach
            }
            val got = Vectorizer.vectorize(raw, schema).columns
            val want = row.getValue("columns").jsonArray.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive }
            if (got.map { it.first } != want.map { it.first }) {
                problems += "$file: 열 이름·순서가 다름"
                return@forEach
            }
            val diff = got.zip(want).filter { (g, w) -> !same(g.second, w.second) }
                .map { (g, w) -> "${g.first}=${g.second} (PC ${w.second.content})" }
            if (diff.isNotEmpty()) problems += "$file: ${diff.joinToString()}"
            n++
        }
        if (problems.isNotEmpty()) fail("${problems.size}개 불일치\n" + problems.joinToString("\n"))
        return n
    }

    private fun sha256(f: File): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    private fun same(got: Number, want: JsonPrimitive): Boolean = when (got) {
        is Double -> got.toRawBits() == want.double.toRawBits()
        else -> want.content == got.toString()
    }
}
