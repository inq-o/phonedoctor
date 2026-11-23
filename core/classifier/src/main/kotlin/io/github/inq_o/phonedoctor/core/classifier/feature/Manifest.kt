package io.github.inq_o.phonedoctor.core.classifier.feature

import java.io.File
import java.io.IOException

/**
 * 특징을 만들 원시 값 (PC `ml/extract/extract.py`의 `parse()` 결과와 같은 모양).
 * [label]은 기기가 준다(시스템 로케일 라벨). PC는 resources.arsc의 ko 라벨을 읽는다
 */
data class RawFeatures(
    /** 매니페스트의 package. 없으면 Python처럼 "None" (PC 벡터의 이름 패턴 계산과 같게) */
    val packageName: String,
    val label: String,
    val permissions: List<String>,
    val intents: List<String>,
    val components: Map<String, List<String>>,
    val minSdk: Int,
    val targetSdk: Int,
    val hasLauncher: Boolean,
    val sizeBytes: Long,
)

class ManifestException(message: String) : Exception(message)

/**
 * base.apk의 AndroidManifest.xml에서 원시 값을 읽는다. androguard `APK`의 같은 이름 함수를 그대로 옮겼다.
 *
 * androguard가 요소를 찾는 방식이 둘이라 그대로 따른다:
 * - `find_tags`(권한·컴포넌트·uses-sdk): 네임스페이스 없는 태그와 android 네임스페이스 태그 둘 다
 * - `get_intent_filters`·`get_main_activities`: 네임스페이스 없는 태그만
 */
object ManifestReader {
    val COMPONENTS = listOf("activity", "service", "receiver", "provider")

    /** [apk]는 base.apk (기기: `ApplicationInfo.sourceDir`). 크기도 이 파일로 잰다 */
    fun read(apk: File, label: String): RawFeatures {
        val manifest = try {
            ApkZip.read(apk, "AndroidManifest.xml")
        } catch (e: ManifestException) {
            throw e
        } catch (e: IOException) {
            throw ManifestException("APK를 읽을 수 없음: ${e.message}")
        }
        return fromManifest(manifest, label, apk.length())
    }

    fun fromManifest(manifest: ByteArray, label: String, sizeBytes: Long): RawFeatures {
        val root = try {
            Axml.parse(manifest)
        } catch (e: AxmlException) {
            throw ManifestException("매니페스트를 읽을 수 없음: ${e.message}")
        }
        if (root.ns.isNotEmpty() || root.tag != "manifest") throw ManifestException("뿌리가 manifest가 아님")

        // androguard `find_tags`: 뿌리가 그 태그면 뿌리만, 아니면 모든 깊이 (android 네임스페이스 태그도). 순서는 문서 순서로 고정
        fun tags(name: String): List<XmlElement> = if (root.ns.isEmpty() && root.tag == name) listOf(root) else root.descendants(name, androidToo = true)

        val pkg: String? = tags("manifest").firstNotNullOfOrNull { it.value("package") }

        // androguard `_format_value`: 값과 패키지가 둘 다 있을 때만. '.'로 시작하거나 '.'이 없으면 패키지명을 붙인다
        fun format(v: String): String {
            if (v.isEmpty() || pkg.isNullOrEmpty()) return v
            return when (v.indexOf('.')) {
                0 -> pkg + v
                -1 -> "$pkg.$v"
                else -> v
            }
        }

        fun names(tag: String): List<String> = tags(tag).mapNotNull { it.value("name") }.map(::format)

        val comps = COMPONENTS.associateWith { names(it) }
        val intents = sortedSetOf<String>()
        for (kind in listOf("activity", "receiver", "service")) {
            val wanted = comps.getValue(kind).toSet()
            // androguard `get_intent_filters`: android:name을 패키지명으로 푼 값이 같은 요소의 intent-filter(모든 깊이) 바로 아래 action·category
            for (item in root.descendants(kind)) {
                val n = item.androidValue("name") ?: continue
                if (format(n) !in wanted) continue
                for (filter in item.descendants("intent-filter")) {
                    for (el in filter.childrenNamed("action") + filter.childrenNamed("category")) {
                        // PC는 이름 없는 action·category에서 예외를 내고 그 APK를 건너뛴다
                        intents += el.androidValue("name") ?: throw ManifestException("이름 없는 ${el.tag}")
                    }
                }
            }
        }
        // uses-sdk가 여럿이면 문서 순서로 처음 값이 있는 것 (extract.py `_uses_sdk`와 같음)
        val usesSdk = tags("uses-sdk")
        val minRaw = usesSdk.firstNotNullOfOrNull { it.value("minSdkVersion") }
        val targetRaw = usesSdk.firstNotNullOfOrNull { it.value("targetSdkVersion") }
        // androguard는 APK를 열 때 권한 표를 고르려고 min·target을 int()로 읽는다: 숫자가 아니면 그 APK를 거절
        if (!targetRaw.isNullOrEmpty() && pyInt(targetRaw) == null) throw ManifestException("targetSdkVersion이 숫자가 아님: $targetRaw")
        val minSdk = if (minRaw.isNullOrEmpty()) 1 else pyInt(minRaw) ?: throw ManifestException("minSdkVersion이 숫자가 아님: $minRaw")
        val targetSdk = (targetRaw?.takeIf { it.isNotEmpty() } ?: minRaw)?.let(::pyInt) ?: 1
        return RawFeatures(
            packageName = pkg ?: "None",
            label = label,
            permissions = names("uses-permission").toSortedSet().toList(),
            intents = intents.toList(),
            components = comps.mapValues { (_, v) -> v.sorted() },
            minSdk = minSdk,
            targetSdk = targetSdk,
            hasLauncher = hasLauncher(root),
            sizeBytes = sizeBytes,
        )
    }

    /** androguard `get_main_activities`: 꺼지지 않은 activity·activity-alias 중 MAIN 액션을 가진 이름과 LAUNCHER 분류를 가진 이름이 겹치면 */
    private fun hasLauncher(root: XmlElement): Boolean {
        val main = mutableSetOf<String>()
        val launcher = mutableSetOf<String>()
        for (item in root.descendants("activity") + root.descendants("activity-alias")) {
            if (item.androidValue("enabled") == "false") continue
            // Python `a or b`: android 쪽이 비었으면 네임스페이스 없는 쪽 (그것은 ""여도 이름으로 씀)
            val name = item.androidValue("name")?.takeIf { it.isNotEmpty() } ?: item.attrs["name"]
            if (item.descendants("action").any { it.androidValue("name") == "android.intent.action.MAIN" }) name?.let { main += it }
            if (item.descendants("category").any { it.androidValue("name") == "android.intent.category.LAUNCHER" }) name?.let { launcher += it }
        }
        return (main intersect launcher).isNotEmpty()
    }

    /** Python `int(str)`: 앞뒤 공백, 부호, 숫자 사이 밑줄 하나, 유니코드 십진 숫자를 받는다 */
    internal fun pyInt(s: String): Int? {
        val t = s.trim { it.isWhitespace() || it == '\u001c' || it == '\u001d' || it == '\u001e' || it == '\u001f' || it == '\u0085' }
        if (t.isEmpty()) return null
        var i = 0
        var negative = false
        if (t[0] == '+' || t[0] == '-') {
            negative = t[0] == '-'
            i = 1
        }
        if (i >= t.length) return null
        var value = 0L
        var lastWasDigit = false
        while (i < t.length) {
            val c = t[i]
            if (c == '_') {
                if (!lastWasDigit || i + 1 >= t.length) return null
                lastWasDigit = false
            } else {
                if (Character.getType(c) != Character.DECIMAL_DIGIT_NUMBER.toInt()) return null
                value = value * 10 + Character.digit(c, 10)
                if (value > Int.MAX_VALUE.toLong() + 1) return null
                lastWasDigit = true
            }
            i++
        }
        if (!lastWasDigit) return null
        val signed = if (negative) -value else value
        return if (signed in Int.MIN_VALUE..Int.MAX_VALUE) signed.toInt() else null
    }
}
