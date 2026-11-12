package io.github.inq_o.phonedoctor.core.data

import android.util.Log
import io.github.inq_o.phonedoctor.core.database.AlertLocalDao
import io.github.inq_o.phonedoctor.core.database.AlertLocalEntity
import io.github.inq_o.phonedoctor.core.database.DatabaseTransactions
import io.github.inq_o.phonedoctor.core.database.DetectorStateDao
import io.github.inq_o.phonedoctor.core.database.DetectorStateEntity
import io.github.inq_o.phonedoctor.core.database.ScanRunDao
import io.github.inq_o.phonedoctor.core.database.SymptomEventDao
import io.github.inq_o.phonedoctor.core.database.SymptomEventEntity
import io.github.inq_o.phonedoctor.core.model.AlertOutcome
import io.github.inq_o.phonedoctor.core.model.AlertStatus
import io.github.inq_o.phonedoctor.core.model.AppIdentity
import io.github.inq_o.phonedoctor.core.model.ScanResult
import io.github.inq_o.phonedoctor.core.model.ScanRun
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import io.github.inq_o.phonedoctor.core.packages.InstalledApps
import io.github.inq_o.phonedoctor.core.usage.DeviceClock
import io.github.inq_o.phonedoctor.core.usage.UsageAccess
import io.github.inq_o.phonedoctor.core.usage.UsageEventsSource
import io.github.inq_o.phonedoctor.core.usage.detector.Detector
import io.github.inq_o.phonedoctor.core.usage.detector.DetectorState
import io.github.inq_o.phonedoctor.core.usage.detector.MetaValidity
import io.github.inq_o.phonedoctor.core.usage.detector.Outcome
import io.github.inq_o.phonedoctor.core.usage.detector.PackageMeta
import io.github.inq_o.phonedoctor.core.usage.detector.UsageQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** 검사 1회의 결과. [opened]는 시니어에게 알릴 요청, [closed]는 알림을 지울 요청 */
data class ScanReport(val run: ScanRun, val opened: List<SymptomAlert>, val closed: List<Long>)

/**
 * 증상 감지 검사 1회 (FR-009~012): 사용 기록 조회 → 판정 → 광고로 센 전환 저장 → 하루 기준 확인.
 * 판정 결과·판정기 상태·확인 요청·검사 기록을 한 트랜잭션으로 저장해, 중간에 죽어도 다시 실행하면 같은 결과가 나온다.
 * 기기 밖으로는 아무것도 보내지 않는다.
 */
@Singleton
class SymptomScanner @Inject constructor(
    private val usageAccess: UsageAccess,
    private val source: UsageEventsSource,
    private val installedApps: InstalledApps,
    private val transactions: DatabaseTransactions,
    private val symptomDao: SymptomEventDao,
    private val stateDao: DetectorStateDao,
    private val scanRunDao: ScanRunDao,
    private val alertDao: AlertLocalDao,
) {
    private val mutex = Mutex()
    private val scanningFlow = MutableStateFlow(false)
    private val json = Json { ignoreUnknownKeys = true }

    /** 검사가 도는 중인지 (화면 표시용) */
    val scanning: StateFlow<Boolean> get() = scanningFlow

    suspend fun scan(trigger: String): ScanReport = mutex.withLock {
        scanningFlow.value = true
        try {
            withContext(Dispatchers.IO) { doScan(trigger) }
        } finally {
            scanningFlow.value = false
        }
    }

    private suspend fun doScan(trigger: String): ScanReport {
        val startedNanos = System.nanoTime()
        val clock = source.clock()
        fun elapsedMs() = (System.nanoTime() - startedNanos) / 1_000_000

        suspend fun skipped(result: ScanResult, note: String = ""): ScanReport {
            val run = ScanRunRecord(clock, trigger, result, note = note, durationMs = elapsedMs())
            val closed = transactions.run {
                val closed = reconcileRemoved(clock.wall)
                scanRunDao.insert(run.toEntity())
                closed
            }
            return ScanReport(run.toModel(), emptyList(), closed)
        }

        if (!usageAccess.isGranted()) return skipped(ScanResult.NO_PERMISSION)
        // 잠금 해제 전 조회는 빈 결과로 올 수 있다. "이벤트 없음"으로 읽지 않게 조회하지 않는다
        if (!clock.unlocked) return skipped(ScanResult.LOCKED)

        return try {
            val saved = stateDao.get()
            val restored = saved?.takeIf { it.schema == Detector.SCHEMA }
                ?.let { runCatching { json.decodeFromString(DetectorState.serializer(), it.json) }.getOrNull() }
            // 처음이거나 저장된 상태를 못 읽으면 지금부터 새로 센다. 그 전 기록은 판정하지 않는다
            val state = restored
                ?: Detector.initState(clock.wall, if (saved == null) "install" else "schema", clock.wall, clock.elapsed, clock.bootCount)
            val (from, to) = Detector.queryRange(state, clock.wall, clock.elapsed, clock.bootCount)
            val events = try {
                source.query(from, to)
            } catch (e: SecurityException) {
                return skipped(ScanResult.NO_PERMISSION, "조회 중 권한 꺼짐")
            }
            val apps = installedApps.snapshot()
            val snapshot = apps.mapValues { PackageMeta(it.value.launcherTargets, it.value.isSystem, it.value.lastUpdateTime) }
            val result = Detector.processQuery(state, UsageQuery(clock.wall, clock.elapsed, clock.bootCount, events), snapshot)

            val ads = result.rows.filter { it.outcome == Outcome.AD }
            // 판정할 때 설치돼 있던 판이 확인된 것만 앱에 귀속한다. 그사이 지워졌거나 업데이트된 앱의 전환은 버린다
            val attributable = ads.filter { it.metaValidity == MetaValidity.VERIFIED && it.pkg != installedApps.ownPackageName }
            val identities = attributable.map { it.pkg }.distinct().associateWith { installedApps.identity(it) }
            val zone = ZoneId.systemDefault()
            val entities = attributable.mapNotNull { row ->
                val id = identities[row.pkg] ?: return@mapNotNull null
                SymptomEventEntity(
                    packageName = id.packageName, signingCertSha256 = id.signingCertSha256, versionCode = id.versionCode,
                    className = row.cls, resumedAt = row.ts,
                    localDate = Instant.ofEpochMilli(row.ts).atZone(zone).toLocalDate().toString(),
                    segmentId = row.segmentId, detectorVersion = SymptomPolicy.DETECTOR_VERSION,
                )
            }
            // 시계 변경으로 이번 조회분을 버렸는지 (판정기가 버린 횟수를 올림)
            val clockDiscarded = (result.state.counts["clock_discarded"] ?: 0L) != (state.counts["clock_discarded"] ?: 0L)
            val run = ScanRunRecord(
                clock, trigger,
                result = when {
                    events == null -> ScanResult.QUERY_NULL
                    clockDiscarded -> ScanResult.CLOCK_DISCARDED
                    else -> ScanResult.OK
                },
                queryFrom = from, queryTo = to,
                eventCount = events?.size ?: 0,
                transitionCount = result.rows.size,
                adCount = ads.size,
                holdCount = result.rows.count { it.outcome == Outcome.HOLD },
                storedCount = entities.size,
                segmentId = result.state.segment.id,
                segmentKind = result.state.segment.kind,
                quarantined = result.state.openQuarantine != null,
                durationMs = elapsedMs(),
            )
            val (opened, closed) = transactions.run {
                symptomDao.insert(entities)
                stateDao.put(
                    DetectorStateEntity(
                        schema = Detector.SCHEMA,
                        json = json.encodeToString(DetectorState.serializer(), result.state),
                        updatedAt = clock.wall,
                    ),
                )
                val opened = entities.groupBy { AppIdentity(it.packageName, it.signingCertSha256, it.versionCode) }
                    .mapNotNull { (app, rows) -> evaluate(app, rows.minOf { it.localDate }, clock.wall) }
                val closed = reconcileRemoved(clock.wall)
                scanRunDao.insert(run.toEntity())
                val cutoff = clock.wall - SymptomPolicy.RETENTION_DAYS * 86_400_000L
                symptomDao.deleteOlderThan(cutoff)
                scanRunDao.deleteOlderThan(cutoff)
                opened to closed
            }
            ScanReport(run.toModel(), opened, closed)
        } catch (e: RuntimeException) {
            // 조회·앱 목록 읽기 실패. 상태를 저장하지 않았으므로 다음 검사가 같은 범위를 다시 읽는다
            Log.w(TAG, "검사 실패", e)
            skipped(ScanResult.ERROR, e.javaClass.simpleName)
        }
    }

    /** 새 근거가 생긴 앱의 하루 횟수를 기준과 비교한다. 시니어에게 알릴 요청이면 돌려준다 */
    private suspend fun evaluate(app: AppIdentity, firstNewDate: String, now: Long): SymptomAlert? {
        val existing = alertDao.find(app.packageName, app.signingCertSha256, app.versionCode)
        val open = existing?.takeIf { it.status == AlertStatus.OPEN.name }
        val since = open?.openedDate ?: firstNewDate
        val days = symptomDao.dailyCounts(app.packageName, app.signingCertSha256, app.versionCode, since)
            .map { DayEvidence(it.localDate, it.count, it.firstAt, it.lastAt) }
        val known = existing?.let {
            ExistingAlert(AlertStatus.valueOf(it.status), it.outcome?.let(AlertOutcome::valueOf), it.date, it.openedDate, it.updatedAt)
        }
        return when (val decision = SymptomPolicy.decide(known, days)) {
            AlertDecision.None -> null
            is AlertDecision.Update -> {
                val e = decision.evidence
                alertDao.update(
                    existing!!.copy(date = e.date, count = e.count, days = decision.days, firstAt = e.firstAt, lastAt = e.lastAt, updatedAt = now),
                )
                null
            }
            is AlertDecision.Open -> {
                val e = decision.evidence
                val label = installedApps.label(app.packageName) ?: app.packageName
                val entity = (existing ?: AlertLocalEntity(
                    packageName = app.packageName, signingCertSha256 = app.signingCertSha256, versionCode = app.versionCode,
                    label = label, source = SOURCE_SYMPTOM, status = AlertStatus.OPEN.name, outcome = null,
                    date = e.date, count = e.count, days = 1, firstAt = e.firstAt, lastAt = e.lastAt,
                    detectorVersion = SymptomPolicy.DETECTOR_VERSION, openedDate = e.date, createdAt = now, updatedAt = now,
                )).copy(
                    label = label, status = AlertStatus.OPEN.name, outcome = null, date = e.date, count = e.count,
                    days = decision.days, firstAt = e.firstAt, lastAt = e.lastAt, openedDate = e.date, updatedAt = now,
                    detectorVersion = SymptomPolicy.DETECTOR_VERSION,
                )
                if (existing == null) entity.copy(id = alertDao.insert(entity)).toModel() else entity.also { alertDao.update(it) }.toModel()
            }
        }
    }

    /**
     * 열린 요청의 앱이 이미 지워졌거나 다른 판으로 바뀌었으면 닫는다. 확인하지 못한 것은 그대로 둔다.
     * 닫은 요청 ID를 돌려준다.
     */
    private suspend fun reconcileRemoved(now: Long): List<Long> = alertDao.open().mapNotNull { alert ->
        val outcome = when (installedApps.isInstalled(alert.packageName)) {
            null -> return@mapNotNull null
            false -> AlertOutcome.ALREADY_REMOVED
            true -> {
                val current = installedApps.identity(alert.packageName) ?: return@mapNotNull null
                if (current.versionCode == alert.versionCode && current.signingCertSha256 == alert.signingCertSha256) return@mapNotNull null
                AlertOutcome.VERSION_CHANGED
            }
        }
        alertDao.update(alert.copy(status = AlertStatus.RESOLVED.name, outcome = outcome.name, updatedAt = now))
        alert.id
    }

    private data class ScanRunRecord(
        val clock: DeviceClock,
        val trigger: String,
        val result: ScanResult,
        val queryFrom: Long? = null,
        val queryTo: Long? = null,
        val eventCount: Int = 0,
        val transitionCount: Int = 0,
        val adCount: Int = 0,
        val holdCount: Int = 0,
        val storedCount: Int = 0,
        val segmentId: Int = 0,
        val segmentKind: String = "",
        val quarantined: Boolean = false,
        val durationMs: Long = 0,
        val note: String = "",
    ) {
        fun toModel() = ScanRun(
            0, clock.wall, trigger, result, queryFrom, queryTo, eventCount, transitionCount, adCount, holdCount,
            storedCount, segmentId, segmentKind, quarantined, durationMs, note,
        )

        fun toEntity() = toModel().toEntity()
    }

    private companion object {
        const val TAG = "PhoneDoctorScan"
        const val SOURCE_SYMPTOM = "symptom"
    }
}
