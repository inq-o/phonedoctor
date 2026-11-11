package io.github.inq_o.phonedoctor.core.usage.detector

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PC 기준 구현(testapps/poc/features/featx.py)과 같은 사례 파일로 판정기를 대조한다.
 * 분석용 모드(order_assume, recover) 사례는 제품 판정에 쓰지 않으므로 건너뛴다.
 * 검사 항목은 featx.py check_cases와 같다: 기대값, 배치 분할, 저장 실패 뒤 재실행, 같은 ms 묶음의 조회 순서 바꾸기, 조회 범위.
 */
class DetectorCasesTest {
    private val json = Json

    private val types = mapOf(
        "RESUMED" to 1, "PAUSED" to 2, "USER_INTERACTION" to 7, "SCREEN_ON" to 15, "SCREEN_OFF" to 16,
        "KEYGUARD_SHOWN" to 17, "KEYGUARD_HIDDEN" to 18, "SHUTDOWN" to 26, "STARTUP" to 27,
    )

    private fun casesFile(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "testapps/poc/features/cases.json")
            if (f.exists()) return f
            dir = dir.parentFile
        }
        error("cases.json을 찾지 못함")
    }

    private fun ev(e: JsonElement): UsageEvent {
        val a = e.jsonArray
        val t = a[1].jsonPrimitive
        val type = if (t.isString) types.getValue(t.content) else t.int
        return UsageEvent(a[0].jsonPrimitive.long, type, a[2].jsonPrimitive.content, if (a.size > 3) a[3].jsonPrimitive.content else "")
    }

    private fun snap(base: JsonObject, extra: JsonObject?): Map<String, PackageMeta> =
        (base + (extra ?: emptyMap())).mapValues { (_, v) ->
            val o = v.jsonObject
            PackageMeta(
                resolved = o["resolved"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet(),
                system = o["system"]?.jsonPrimitive?.booleanOrNull ?: false,
                since = o["since"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.longOrNull,
            )
        }

    /** 배치 사이 저장·복구를 흉내 내며 조회를 차례로 처리 */
    private fun run(queries: List<UsageQuery>, snap: Map<String, PackageMeta>, state: DetectorState): Pair<List<DetectorRow>, DetectorState> {
        var st = state
        val rows = mutableListOf<DetectorRow>()
        for (q in queries) {
            val r = Detector.processQuery(st, q, snap)
            rows += r.rows
            st = json.decodeFromString(DetectorState.serializer(), json.encodeToString(DetectorState.serializer(), r.state))
        }
        return rows to st
    }

    private fun featureJson(f: Feature): String {
        val v = f.num?.toString() ?: f.bool?.toString() ?: "null"
        return "[$v, \"${f.status}\"]"
    }

    /** 배치 분할과 무관해야 하는 부분만 (조회 번호·순번·행 번호 제외) */
    private fun comparable(rows: List<DetectorRow>): List<String> = rows.map { r ->
        listOf(
            r.segmentId, r.ts, r.pkg, r.cls, Detector.FEATURES.joinToString { featureJson(r.features.getValue(it)) },
            r.outcome, r.failed, r.unknown, r.metaValidity,
        ).joinToString("|")
    }.sorted()

    private fun single(events: List<UsageEvent>, start: Long, elapsed0: Long = 1_000_000): List<UsageQuery> {
        val end = events.maxOf { it.ts } + Detector.SETTLE_MS + 1
        return listOf(UsageQuery(end, elapsed0 + end - start, 1, events))
    }

    private fun splits(events: List<UsageEvent>, start: Long, elapsed0: Long = 1_000_000): List<Pair<Long, List<UsageQuery>>> {
        val ts = events.map { it.ts }.toSortedSet().toList()
        val end = ts.last() + Detector.SETTLE_MS + 1
        val bounds = (ts.flatMap { listOf(it, it + 1) }.toSortedSet() - ts.first())
        return bounds.map { b ->
            val w1 = b + Detector.SETTLE_MS
            b to listOf(
                UsageQuery(w1, elapsed0 + w1 - start, 1, events.filter { it.ts < w1 }),
                UsageQuery(end, elapsed0 + end - start, 1, events),
            )
        }
    }

    /** 같은 ms 묶음 안의 조회 순서를 바꾼 판 두 가지 (뒤집기, 한 칸 돌리기) */
    private fun shuffles(events: List<UsageEvent>): List<List<UsageEvent>> {
        val groups = mutableListOf<MutableList<UsageEvent>>()
        for (e in events) {
            if (groups.isNotEmpty() && groups.last()[0].ts == e.ts) groups.last() += e else groups += mutableListOf(e)
        }
        val variants = listOf<(List<UsageEvent>) -> List<UsageEvent>>({ it.reversed() }, { it.drop(1) + it.take(1) })
        return variants.map { f -> groups.flatMap(f) }.filter { it != events }
    }

    /** 조회마다 저장 실패 → 같은 상태로 다시 실행해도 결과가 같은지 */
    private fun replays(qs: List<UsageQuery>, snap: Map<String, PackageMeta>, st0: DetectorState): List<List<DetectorRow>> =
        qs.indices.map { i ->
            val (rows, st) = run(qs.take(i), snap, st0)
            Detector.processQuery(st, qs[i], snap)   // 버려지는 실행
            rows + run(qs.drop(i), snap, st).first
        }

    private fun expectFeature(e: JsonElement): String {
        val a = e.jsonArray
        val v = a[0]
        val s = if (v is JsonNull) "null" else (v as JsonPrimitive).content
        return "[$s, \"${a[1].jsonPrimitive.content}\"]"
    }

    @Test
    fun casesMatchReferenceImplementation() {
        val spec = json.parseToJsonElement(casesFile().readText()).jsonObject
        val base = spec.getValue("launchers").jsonObject
        val fails = mutableListOf<String>()
        var checked = 0
        for (caseEl in spec.getValue("cases").jsonArray) {
            val case = caseEl.jsonObject
            if (case["order_assume"].present() || case["recover"].present()) continue
            checked++
            val id = case.getValue("id").jsonPrimitive.content
            val msgs = mutableListOf<String>()
            val snap = snap(base, case["launchers"]?.jsonObject)
            val segStart = case["segment"]?.jsonObject?.getValue("start")?.jsonPrimitive?.long ?: -Detector.CAP_MS
            val segKind = case["segment"]?.jsonObject?.getValue("kind")?.jsonPrimitive?.content ?: "install"
            val rows: List<DetectorRow>
            val finalState: DetectorState
            if ("queries" in case) {
                val raw = case.getValue("queries").jsonArray.map { it.jsonObject }
                val qs = raw.map { q ->
                    UsageQuery(
                        wallTo = q.getValue("wall_to").jsonPrimitive.long,
                        elapsed = q.getValue("elapsed").jsonPrimitive.long,
                        bootCount = q["boot_count"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.int,
                        events = q.getValue("events").let { if (it is JsonNull) null else it.jsonArray.map(::ev) },
                    )
                }
                val init = case["init"]?.jsonObject
                val st0 = Detector.initState(
                    segStart, segKind,
                    wall = init?.get("wall")?.jsonPrimitive?.long,
                    elapsed = init?.get("elapsed")?.jsonPrimitive?.long,
                    bootCount = init?.get("boot_count")?.takeIf { it !is JsonNull }?.jsonPrimitive?.int,
                )
                val r = run(qs, snap, st0)
                rows = r.first
                finalState = r.second
                val ref = comparable(rows)
                var s = st0
                raw.forEachIndexed { i, q ->
                    val got = Detector.queryRange(s, qs[i].wallTo, qs[i].elapsed, qs[i].bootCount)
                    q["range"]?.jsonArray?.let { want ->
                        if (got.toList() != want.map { it.jsonPrimitive.long }) msgs += "조회 ${i + 1} 범위 = $got (기대 $want)"
                    }
                    s = Detector.processQuery(s, qs[i], snap).state
                }
                replays(qs, snap, st0).forEachIndexed { i, r2 -> if (comparable(r2) != ref) msgs += "조회 ${i + 1} 재실행 결과가 다름" }
                for (k in 0 until 2) {
                    val sq = qs.map { q ->
                        if (q.events == null) q else q.copy(events = (shuffles(q.events) + listOf(q.events, q.events))[k])
                    }
                    if (sq != qs && comparable(run(sq, snap, st0).first) != ref) msgs += "같은 ms 묶음의 조회 순서를 바꾸면 결과가 다름"
                }
            } else {
                val flat = case.getValue("events").jsonArray.map(::ev)
                fun mk() = Detector.initState(segStart, segKind, wall = segStart, elapsed = 1_000_000, bootCount = 1)
                val r = run(single(flat, segStart), snap, mk())
                rows = r.first
                finalState = r.second
                val ref = comparable(rows)
                for ((b, qs2) in splits(flat, segStart)) {
                    if (comparable(run(qs2, snap, mk()).first) != ref) msgs += "배치 분할 ${b}에서 결과가 다름"
                    replays(qs2, snap, mk()).forEachIndexed { i, r2 ->
                        if (comparable(r2) != ref) msgs += "분할 $b 조회 ${i + 1} 재실행 결과가 다름"
                    }
                }
                for (sh in shuffles(flat)) {
                    if (comparable(run(single(sh, segStart), snap, mk()).first) != ref) msgs += "같은 ms 묶음의 조회 순서를 바꾸면 결과가 다름"
                }
            }
            for (exEl in case.getValue("expect").jsonArray) {
                val ex = exEl.jsonObject
                val target = ex.getValue("target").jsonArray
                val t = target[0].jsonPrimitive.long
                val p = target[1].jsonPrimitive.content
                val c = target[2].jsonPrimitive.content
                val hit = rows.filter { it.ts == t && it.pkg == p && it.cls == c }
                val each = ex["each"]?.jsonArray?.map { it.jsonObject } ?: List(ex["rows"]?.jsonPrimitive?.int ?: 1) { ex }
                if (hit.size != each.size) {
                    msgs += "$target 행 ${hit.size}개 (기대 ${each.size})"
                    continue
                }
                hit.zip(each).forEachIndexed { n, (r, x) ->
                    val tag = "$target#$n"
                    x["features"]?.jsonObject?.forEach { (k, v) ->
                        val got = featureJson(r.features.getValue(k))
                        if (got != expectFeature(v)) msgs += "$tag $k = $got (기대 ${expectFeature(v)})"
                    }
                    x["rule_v0t"]?.jsonObject?.let { rule ->
                        rule["outcome"]?.let { if (r.outcome.name != it.jsonPrimitive.content) msgs += "$tag outcome = ${r.outcome} (기대 $it)" }
                        rule["failed"]?.let { w -> if (r.failed != w.strings()) msgs += "$tag failed = ${r.failed} (기대 $w)" }
                        rule["unknown"]?.let { w -> if (r.unknown != w.strings()) msgs += "$tag unknown = ${r.unknown} (기대 $w)" }
                    }
                    x["meta_validity"]?.let {
                        if (r.metaValidity.name.lowercase() != it.jsonPrimitive.content) msgs += "$tag meta_validity = ${r.metaValidity} (기대 $it)"
                    }
                }
            }
            case["counts"]?.jsonObject?.forEach { (k, v) ->
                val got = finalState.counts[k] ?: 0
                if (got != v.jsonPrimitive.long) msgs += "counts.$k = $got (기대 $v)"
            }
            case["segments"]?.jsonArray?.let { want ->
                val got = rows.map { it.segmentId }
                if (got != want.map { it.jsonPrimitive.int }) msgs += "segment_id 열 = $got (기대 $want)"
            }
            if (msgs.isNotEmpty()) fails += "사례 $id ${case.getValue("title").jsonPrimitive.content}\n  " + msgs.joinToString("\n  ")
        }
        assertTrue("기본 모드 사례가 너무 적음: $checked", checked >= 40)
        assertEquals("실패한 사례:\n" + fails.joinToString("\n"), 0, fails.size)
    }

    private fun JsonElement?.present() = this != null && this !is JsonNull
    private fun JsonElement.strings() = (this as JsonArray).map { it.jsonPrimitive.content }
}
