package io.github.inq_o.phonedoctor.core.usage.detector

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 증상 감지 판정기. 사용 기록의 화면 전환(RESUMED)마다 특징 F0~F10을 뽑고 규칙 v0의 시간 기준 판(rule_v0t)으로
 * 광고(AD)·정상(NORMAL)·보류(HOLD)를 가른다.
 *
 * PC 기준 구현 `testapps/poc/features/featx.py`의 기본 모드(같은 ms 순서 가정 없음, SHUTDOWN 해제 없음)를 그대로 옮겼고,
 * 같은 사례 파일(`cases.json`)로 결과를 대조한다(DetectorCasesTest). 가정과 미확인 사항은 그 폴더 README.
 *
 * - 커서는 워터마크다. 조회 끝 [SETTLE_MS] 앞의 이벤트만 확정하고 나머지는 다음 조회에서 다시 받는다
 * - 같은 ms 묶음의 순서는 모르므로 가능한 순서를 모두 계산한다. 결과가 갈리면 HOLD
 * - 재부팅·시계 변경·긴 공백이면 상태를 비우고 새 관측 구간을 연다. 어느 시계의 기록인지 가를 수 없는 범위는 행을 만들지 않는다
 */
object Detector {
    const val SCHEMA = "fx0-draft"
    const val CAP_MS = 600_000L
    const val SETTLE_MS = 5_000L
    const val CLOCK_TOL_MS = 1_000L
    const val OS_SHIFT_MS = 2_000L
    const val RETENTION_SAFE_MS = 3 * 86_400_000L
    const val MAX_TIE = 6
    const val MAX_WORLDS = 64
    const val HANDOFF_MS = 2_000L
    const val LOOKBACK_MS = 3_000L

    const val RESUMED = 1
    const val PAUSED = 2
    const val USER_INTERACTION = 7
    const val SCREEN_ON = 15
    const val SCREEN_OFF = 16
    const val KEYGUARD_SHOWN = 17
    const val KEYGUARD_HIDDEN = 18
    const val SHUTDOWN = 26
    const val STARTUP = 27

    /** 특징에 영향을 주는 종류 */
    private val ORDERED = setOf(RESUMED, PAUSED, USER_INTERACTION, SCREEN_ON, SCREEN_OFF, KEYGUARD_HIDDEN)

    /** 판정기가 쓰는 종류 전부. 수집기는 이것만 넘기면 된다 */
    val RELEVANT_TYPES: Set<Int> = ORDERED + setOf(KEYGUARD_SHOWN, SHUTDOWN, STARTUP)

    val FEATURES = listOf("F0", "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10")
    val RULES = listOf("R1", "R2", "R3", "R4", "R5")

    fun initState(
        start: Long,
        kind: String = "install",
        wall: Long? = null,
        elapsed: Long? = null,
        bootCount: Int? = null,
    ) = DetectorState(
        schema = SCHEMA,
        cursor = start,
        skipUntil = start,
        segment = Segment(1, start, kind),
        worlds = listOf(World()),
        last = LastQuery(wall ?: start, elapsed ?: 0, bootCount),
    )

    /**
     * 수집기가 queryEvents에 넘길 [begin, end). 시계가 뒤로 가면 옛 커서가 [wall]보다 클 수 있어 기준을 다시 계산한다.
     * STARTUP으로만 알 수 있는 재부팅을 놓치지 않게 옛 커서와 새 커서 중 앞쪽부터 읽는다.
     */
    fun queryRange(state: DetectorState, wall: Long, elapsed: Long, bootCount: Int?): Pair<Long, Long> {
        val st = Work(state)
        rebase(st, wall, elapsed, bootCount, emptyList())
        return min(min(state.cursor, st.cursor), wall) to wall
    }

    /** 조회 1회 처리. 돌려준 상태와 행을 함께 저장해야 하고, 저장에 실패하면 이전 상태로 다시 부르면 된다 */
    fun processQuery(
        state: DetectorState,
        query: UsageQuery,
        snapshot: Map<String, PackageMeta>,
        systemFilter: Boolean = true,
    ): QueryResult {
        val st = Work(state)
        val queryId = st.nextQuery
        st.nextQuery += 1
        if (query.events == null) {
            st.bump("query_null")
            return QueryResult(st.toState(), emptyList())
        }
        val wall = query.wallTo
        val events = query.events.mapIndexed { i, e -> Ev(e.ts, e.type, e.pkg, e.cls, i) }
        val rows = mutableListOf<DetectorRow>()
        val ok = rebase(st, wall, query.elapsed, query.bootCount, events)
        val maxTs = (events.map { it.ts } + listOfNotNull(st.bootMaxTs)).maxOrNull()
        if (maxTs != null) st.bootMaxTs = maxTs
        if (!ok) return QueryResult(st.toState(), rows)
        if (wall - st.cursor > RETENTION_SAFE_MS) {
            val start = wall - RETENTION_SAFE_MS
            st.bump("gap_discarded", events.count { it.ts >= st.cursor && it.ts < start })
            st.cursor = start
            val q = st.openQ
            if (q != null) {
                st.openQ = q.copy(floor = max(q.floor, start))
            } else {
                st.skipUntil = max(st.skipUntil!!, start)
                reset(st, st.skipUntil!!, "gap")
            }
        }
        val fin = wall - SETTLE_MS
        val cur = st.cursor
        val skip = st.skipUntil ?: fin
        st.bump("pre_cursor_ignored", events.count { it.ts < cur })
        st.bump("uncertain_discarded", events.count { it.ts >= cur && it.ts < min(skip, fin) })
        val todo = events.filter { it.ts >= max(cur, skip) && it.ts < fin }.sortedBy { it.ts }
        var i = 0
        while (i < todo.size) {
            var j = i
            while (j < todo.size && todo[j].ts == todo[i].ts) j++
            val group = todo.subList(i, j)
            while (st.pending.isNotEmpty() && st.pending[0].start <= group[0].ts) {
                val p = st.pending.removeAt(0)
                reset(st, p.start, p.kind)
            }
            processGroup(st, group, snapshot, queryId, rows, systemFilter)
            i = j
        }
        while (st.pending.isNotEmpty() && st.pending[0].start < fin) {
            val p = st.pending.removeAt(0)
            reset(st, p.start, p.kind)
        }
        st.cursor = max(cur, fin)
        return QueryResult(st.toState(), rows)
    }

    // ---------- 내부 ----------

    private data class Ev(val ts: Long, val type: Int, val pkg: String, val cls: String, val seq: Int)

    /** 한 조회를 처리하는 동안만 쓰는 변경 가능한 사본 */
    private class Work(s: DetectorState) {
        val schema = s.schema
        var cursor = s.cursor
        var skipUntil = s.skipUntil
        var seg = s.segment
        var worlds = s.worlds
        val pending = s.pending.toMutableList()
        var uncertain = s.uncertain
        var openQ = s.openQuarantine
        var bootMaxTs = s.bootMaxTs
        var last = s.last
        var nextQuery = s.nextQuery
        var nextIngest = s.nextIngest
        val counts = s.counts.toMutableMap()

        fun bump(key: String, n: Int = 1) {
            counts[key] = (counts[key] ?: 0) + n
        }

        fun toState() = DetectorState(
            schema, cursor, skipUntil, seg, worlds, pending.toList(), uncertain, openQ, bootMaxTs, last,
            nextQuery, nextIngest, counts.toSortedMap(),
        )
    }

    private fun reset(st: Work, start: Long, kind: String) {
        st.seg = Segment(st.seg.id + 1, start, kind)
        st.worlds = listOf(World())
        st.bump("segments_$kind")
    }

    /**
     * 시계 기준이 바뀜. [from]부터 다시 읽되 [from, to)는 옛 시계 기록과 섞일 수 있어 쓰지 않고, 새 구간은 그 끝에서 시작한다.
     * 앞서 버리기로 한 범위가 남아 있으면 합친다(줄이면 옛 기록이 다시 판정됨). [to]가 null이면 끝을 모르는 열린 격리다.
     */
    private fun uncertain(st: Work, from: Long, to: Long?, kind: String, after: Long? = null) {
        st.cursor = from
        st.pending.clear()
        st.uncertain = (st.uncertain + UncertainRange(from, to, kind)).takeLast(20)
        val q = st.openQ
        if (q != null) {
            st.openQ = q.copy(floor = max(q.floor, to ?: from), closable = q.closable && to != null)
            return
        }
        if (to == null) {
            st.openQ = OpenQuarantine(after, 0, max(st.skipUntil!!, from), true, kind)
            st.skipUntil = null
            return
        }
        st.skipUntil = max(max(st.skipUntil!!, from), to)
        reset(st, st.skipUntil!!, kind)
    }

    /** 조회 시각(wall·elapsed·BOOT_COUNT)과 새 STARTUP으로 재부팅·시계 변경을 판별. 이번 조회분을 버려야 하면 false */
    private fun rebase(st: Work, wall: Long, el: Long, bc: Int?, events: List<Ev>): Boolean {
        val last = st.last
        st.last = LastQuery(wall, el, bc)
        val base = wall - el
        val drift = (wall - last.wall) - (el - last.elapsed)
        val starts = events.filter { it.type == STARTUP && it.ts >= last.wall }.map { it.ts }.toSortedSet().toList()
        val rebooted = el < last.elapsed || (bc != null && last.bootCount != null && bc != last.bootCount)
        val open = st.openQ
        if (open != null && (rebooted || starts.isNotEmpty())) {
            st.openQ = open.copy(closable = false)
        }
        if (!rebooted && starts.isNotEmpty() && abs(drift) <= CLOCK_TOL_MS) {
            // elapsed가 이어지는 STARTUP: 런타임 재시작. 기기가 꺼져 있던 게 아니라 "없음"을 확정하지 않음
            st.pending += starts.map { PendingBoundary(it, "restart") }
            return true
        }
        if (rebooted || starts.any { it >= base - CLOCK_TOL_MS }) {
            val oldMax = st.bootMaxTs
            st.bootMaxTs = null
            if (base < last.wall - CLOCK_TOL_MS) {
                // 재부팅하면서 시계도 뒤로 감. 옛 부팅이 남긴 기록의 끝을 지금 시각만으로 알 수 없음 → 열린 격리
                val oldBase = last.wall - last.elapsed
                uncertain(st, base, null, "boot_clock", if (oldMax == null) oldBase else max(oldBase, oldMax))
                return true
            }
            val bounds = starts.filter { it >= base - CLOCK_TOL_MS }.toSet().ifEmpty { setOf(base) }
            st.pending += (bounds + starts).sorted().map { PendingBoundary(it, "boot") }
            return true
        }
        if (abs(drift) > CLOCK_TOL_MS) {
            // 어느 이벤트가 바뀌기 전 시계인지 모름 → 이번 조회분은 버림. 버릴 범위의 끝은 OS가 기록을 옮긴 경우와 아닌 경우 중 늦은 쪽
            st.bump("clock_discarded", events.size)
            st.openQ?.let { st.openQ = it.copy(shift = it.shift + drift) }
            val keep = last.wall + (el - last.elapsed)
            val moved = if (drift > 0) st.cursor + drift + 2 * OS_SHIFT_MS else keep
            uncertain(st, wall - SETTLE_MS, max(keep, moved), "clock")
            return false
        }
        return true
    }

    private fun apply(w: World, e: Ev): World = when (e.type) {
        RESUMED -> w.copy(prev = Prev(e.pkg, e.cls, e.ts), offAfterPrev = false)
        PAUSED -> w.copy(paused = w.paused + (e.pkg to e.ts))
        USER_INTERACTION -> w.copy(ui = w.ui + (e.pkg to e.ts))
        SCREEN_ON -> w.copy(on = e.ts)
        KEYGUARD_HIDDEN -> w.copy(unlock = e.ts)
        SCREEN_OFF -> w.copy(off = ScreenOff(e.ts, w.prev?.pkg, w.prev?.cls), offAfterPrev = true)
        else -> w
    }

    /** [CAP_MS]보다 오래된 창 기반 상태는 지운다. 특징 계산도 같은 창을 쓰므로 정리 시점과 무관하게 값이 같다 */
    private fun prune(w: World, now: Long) = w.copy(
        paused = w.paused.filterValues { now - it <= CAP_MS },
        ui = w.ui.filterValues { now - it <= CAP_MS },
        on = w.on?.takeIf { now - it <= CAP_MS },
        unlock = w.unlock?.takeIf { now - it <= CAP_MS },
    )

    /** 판정 시점 스냅샷의 패키지 정보. 이벤트 뒤에 설치·업데이트됐으면 그 이벤트에는 쓰지 않는다 */
    private fun meta(snap: Map<String, PackageMeta>, pkg: String, t: Long): Pair<PackageMeta?, MetaValidity> {
        val info = snap[pkg] ?: return null to MetaValidity.ABSENT
        val since = info.since ?: return info to MetaValidity.UNVERIFIED
        return if (since > t) null to MetaValidity.UPDATED_AFTER_EVENT else info to MetaValidity.VERIFIED
    }

    private fun num(v: Long) = Feature(num = v, status = FeatureStatus.OBSERVED)
    private fun bool(v: Boolean) = Feature(bool = v, status = FeatureStatus.OBSERVED)
    private fun none(s: FeatureStatus) = Feature(status = s)

    private fun features(w: World, seg: Segment, t: Long, p: String, c: String, snap: Map<String, PackageMeta>): Map<String, Feature> {
        val age = t - seg.start
        val boot = seg.kind == "boot"   // 부팅 구간: 시작 전엔 기기가 꺼져 있었으므로 "없음"이 확정
        val noneState = none(if (boot) FeatureStatus.NONE_IN_WINDOW else FeatureStatus.NOT_COVERED)
        fun win(ts: Long?): Feature = when {
            ts != null && t - ts <= CAP_MS -> num(t - ts)
            boot || age >= CAP_MS -> none(FeatureStatus.NONE_IN_WINDOW)
            else -> none(FeatureStatus.NOT_COVERED)
        }
        val prev = w.prev
        val off = w.off
        val info = meta(snap, p, t).first
        return mapOf(
            "F0" to num(min(age, CAP_MS)),
            "F1" to win(w.ui[p]),
            "F2" to if (prev != null) bool(prev.pkg == p) else noneState,
            "F3" to if (prev != null) num(min(t - prev.ts, CAP_MS)) else noneState,
            "F4" to win(w.paused[p]),
            "F5" to if (prev != null) bool(w.offAfterPrev) else noneState,
            "F6" to win(w.on),
            "F7" to win(w.unlock),
            "F8" to when {
                off == null -> noneState
                off.beforePkg == null -> none(FeatureStatus.NOT_COVERED)   // 꺼질 때 앞 화면을 몰랐음
                else -> bool(off.beforePkg == p && off.beforeCls == c)
            },
            "F9" to if (info != null) bool(c in info.resolved) else none(FeatureStatus.LOOKUP_FAILED),
            "F10" to if (info != null) bool(info.system) else none(FeatureStatus.LOOKUP_FAILED),
        )
    }

    // 3값 논리: true·false·null(모름)
    private fun le(f: Feature, thr: Long, age: Long): Boolean? = when (f.status) {
        FeatureStatus.OBSERVED -> f.num!! <= thr
        FeatureStatus.NONE_IN_WINDOW -> false
        FeatureStatus.NOT_COVERED -> if (age >= thr) false else null   // 구간이 thr보다 길면 thr 안에는 확실히 없음
        else -> null
    }

    private fun b(f: Feature): Boolean? = when (f.status) {
        FeatureStatus.OBSERVED -> f.bool
        FeatureStatus.NONE_IN_WINDOW -> false
        else -> null
    }

    private fun and(vararg xs: Boolean?): Boolean? = if (xs.any { it == false }) false else if (xs.all { it == true }) true else null
    private fun or(vararg xs: Boolean?): Boolean? = if (xs.any { it == true }) true else if (xs.all { it == false }) false else null
    private fun not(x: Boolean?): Boolean? = x?.not()

    /**
     * 조건별 '실패' 여부(true=실패, false=통과, null=모름). R1·R5의 LOOKUP_FAILED는 통과로 둔다(아이콘 숨긴 앱을 놓치지 않게).
     *
     * R1 시스템 앱, R2 같은 앱 안 전환, R3 직전 3초 안 그 앱의 사용자 조작, R4 화면 켜짐·잠금 해제 3초 안 끄기 전 화면 복귀,
     * R5 런처 아이콘이 여는 액티비티. 하나라도 실패면 정상 전환이다.
     */
    private fun ruleV0t(f: Map<String, Feature>, age: Long, systemFilter: Boolean): Map<String, Boolean?> {
        val f10 = f.getValue("F10")
        val f9 = f.getValue("F9")
        return mapOf(
            "R1" to if (systemFilter && f10.status == FeatureStatus.OBSERVED) f10.bool else false,
            "R2" to and(b(f.getValue("F2")), not(b(f.getValue("F5"))), le(f.getValue("F4"), HANDOFF_MS, age)),
            "R3" to le(f.getValue("F1"), LOOKBACK_MS, age),
            "R4" to and(or(le(f.getValue("F6"), LOOKBACK_MS, age), le(f.getValue("F7"), LOOKBACK_MS, age)), b(f.getValue("F8"))),
            "R5" to if (f9.status == FeatureStatus.OBSERVED) f9.bool else false,
        )
    }

    /**
     * 묶음 안에서 가능한 처리 순서. 조회 순서는 근거로 쓰지 않는다.
     * 똑같은 이벤트(종류·패키지·클래스)끼리는 바꿔도 결과가 같으므로 조회 순서로 고정한다.
     */
    private fun orders(group: List<Ev>): List<List<Ev>> {
        val rel = group.filter { it.type in ORDERED }
        val out = mutableListOf<List<Ev>>()
        val used = BooleanArray(rel.size)
        val cur = ArrayList<Ev>(rel.size)
        fun sameKey(a: Ev, b: Ev) = a.type == b.type && a.pkg == b.pkg && a.cls == b.cls
        fun go() {
            if (cur.size == rel.size) {
                out += cur.toList()
                return
            }
            for (k in rel.indices) {
                if (used[k]) continue
                // 같은 키의 앞선 이벤트가 아직 안 쓰였으면 이 자리에는 둘 수 없음
                if ((0 until k).any { !used[it] && sameKey(rel[it], rel[k]) }) continue
                used[k] = true
                cur += rel[k]
                go()
                cur.removeAt(cur.size - 1)
                used[k] = false
            }
        }
        go()
        return out
    }

    private fun processGroup(
        st: Work,
        group: List<Ev>,
        snap: Map<String, PackageMeta>,
        queryId: Long,
        rows: MutableList<DetectorRow>,
        systemFilter: Boolean,
    ) {
        val seg = st.seg
        val targets = group.filter { it.type == RESUMED }
        if (group.count { it.type in ORDERED } > MAX_TIE) {
            // 순서를 다 셀 수 없음 → 판정 보류하고 새 구간
            val tied = FEATURES.associateWith { none(FeatureStatus.TIED) }
            val unknownRules: Map<String, Boolean?> = RULES.associateWith { null }
            for (e in targets) rows += row(st, e, queryId, tied, listOf(unknownRules), meta(snap, e.pkg, e.ts).second)
            reset(st, group[0].ts, "tie_overflow")
            return
        }
        val per = targets.associate { it.seq to mutableListOf<Pair<Map<String, Feature>, Map<String, Boolean?>>>() }
        val worlds = LinkedHashSet<World>()
        for (w0 in st.worlds) {
            for (order in orders(group)) {
                var w = w0
                for (e in order) {
                    if (e.type == RESUMED) {
                        val f = features(w, seg, e.ts, e.pkg, e.cls, snap)
                        per.getValue(e.seq) += f to ruleV0t(f, e.ts - seg.start, systemFilter)
                    }
                    w = apply(w, e)
                }
                worlds += prune(w, group[0].ts)
            }
        }
        for (e in targets) {
            val all = per.getValue(e.seq)
            val first = all[0].first
            val merged = FEATURES.associateWith { k ->
                if (all.all { it.first[k] == first[k] }) first.getValue(k) else none(FeatureStatus.TIED)
            }
            rows += row(st, e, queryId, merged, all.map { it.second }, meta(snap, e.pkg, e.ts).second)
        }
        st.worlds = worlds.sortedBy { canonical(it) }
        if (worlds.size > MAX_WORLDS) reset(st, group[0].ts + 1, "world_overflow")
    }

    /** 저장한 상태가 실행마다 같도록 세계 순서를 고정하는 키 */
    private fun canonical(w: World) =
        "${w.prev}|${w.offAfterPrev}|${w.paused.toSortedMap()}|${w.ui.toSortedMap()}|${w.on}|${w.unlock}|${w.off}"

    private fun row(
        st: Work,
        e: Ev,
        queryId: Long,
        f: Map<String, Feature>,
        ruleWorlds: List<Map<String, Boolean?>>,
        metaValidity: MetaValidity,
    ): DetectorRow {
        val failed = RULES.filter { r -> ruleWorlds.all { it[r] == true } }
        val passed = RULES.filter { r -> ruleWorlds.all { it[r] == false } }
        val unknown = RULES.filter { it !in failed && it !in passed }
        val outs = ruleWorlds.map { rw ->
            when {
                rw.values.any { it == true } -> Outcome.NORMAL
                rw.values.any { it == null } -> Outcome.HOLD
                else -> Outcome.AD
            }
        }.toSet()
        val row = DetectorRow(
            segmentId = st.seg.id, ingestId = st.nextIngest, queryId = queryId, sourceSeq = e.seq,
            ts = e.ts, pkg = e.pkg, cls = e.cls, features = f, metaValidity = metaValidity,
            outcome = outs.singleOrNull() ?: Outcome.HOLD, failed = failed, unknown = unknown,
        )
        st.nextIngest += 1
        return row
    }
}
