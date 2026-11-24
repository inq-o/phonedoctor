package io.github.inq_o.phonedoctor.core.classifier.feature

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ln1p

/** 특징 스키마 v1 (`ml/schema/feature_schema_v1.json`). 앱은 빌드 때 복사한 asset을 읽는다 */
class FeatureSchema(
    val version: Int,
    val permissions: List<String>,
    val intents: List<String>,
    val adSdkVocab: List<String>,
    val adSdkCountOver: List<String>,
    val adSdkRules: Map<String, List<String>>,
    val cleanerKeywords: List<String>,
) {
    companion object {
        const val ASSET = "feature_schema_v1.json"

        fun parse(text: String): FeatureSchema {
            val root = Json.parseToJsonElement(text).jsonObject
            val groups = root.getValue("groups").jsonArray.associate {
                val g = it.jsonObject
                g.getValue("name").jsonPrimitive.content to g
            }

            fun strings(g: String, key: String) = groups.getValue(g).getValue(key).jsonArray.map { it.jsonPrimitive.content }
            val rules = groups.getValue("ad_sdk").getValue("rules").jsonObject.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content } }
            return FeatureSchema(
                version = root.getValue("featureSchemaVersion").jsonPrimitive.int,
                permissions = strings("permissions", "vocab"),
                intents = strings("intent_filters", "vocab"),
                adSdkVocab = strings("ad_sdk", "vocab"),
                adSdkCountOver = strings("ad_sdk", "count_over"),
                adSdkRules = rules,
                cleanerKeywords = strings("cleaner_name_pattern", "keywords"),
            )
        }

        private fun JsonObject.getValue(key: String) = get(key) ?: error("스키마에 $key 없음")
    }
}

/** 열 이름과 값. 열 순서는 스키마 groups 순서 (PC `vectorize()`와 같음) */
data class FeatureVector(val columns: List<Pair<String, Number>>) {
    val values: FloatArray get() = FloatArray(columns.size) { columns[it].second.toFloat() }
}

object Vectorizer {
    /** MH-1M 규칙: '.'로 나눈 마지막 조각을 소문자로 */
    fun norm(name: String): String = name.substringAfterLast('.').lowercase()

    fun vectorize(raw: RawFeatures, s: FeatureSchema): FeatureVector {
        val perms = raw.permissions.map(::norm).toSet()
        val intents = raw.intents.map(::norm).toSet()
        val all = raw.components.values.flatten()
        val hits = s.adSdkRules.filter { (_, prefixes) -> all.any { c -> prefixes.any { c.startsWith(it) } } }.keys
        // Python lower()와 같게 로케일 없이
        val text = "${raw.label} ${raw.packageName}".lowercase()
        val cols = mutableListOf<Pair<String, Number>>()
        s.permissions.forEach { cols += "perm::$it" to (if (it in perms) 1 else 0) }
        s.intents.forEach { cols += "intent::$it" to (if (it in intents) 1 else 0) }
        cols += "apk_size_mb_log1p" to round6(ln1p(raw.sizeBytes / 1_048_576.0))
        cols += "ad_sdk_count" to (hits intersect s.adSdkCountOver.toSet()).size
        s.adSdkVocab.forEach { cols += "ad_sdk::$it" to (if (it in hits) 1 else 0) }
        ManifestReader.COMPONENTS.forEach { cols += "n_$it" to raw.components.getValue(it).size }
        cols += "min_sdk" to raw.minSdk
        cols += "target_sdk" to raw.targetSdk
        cols += "launcher_icon_missing" to (if (raw.hasLauncher) 0 else 1)
        cols += "cleaner_name_pattern" to (if (s.cleanerKeywords.any { it in text }) 1 else 0)
        return FeatureVector(cols)
    }

    /** Python `round(x, 6)`: 이진 부동소수 값을 그대로 십진으로 보고 짝수 쪽으로 반올림 */
    private fun round6(x: Double): Double = BigDecimal(x).setScale(6, RoundingMode.HALF_EVEN).toDouble()
}
