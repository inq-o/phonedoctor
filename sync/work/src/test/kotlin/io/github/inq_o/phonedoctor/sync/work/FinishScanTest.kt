package io.github.inq_o.phonedoctor.sync.work

import io.github.inq_o.phonedoctor.core.data.ScanReport
import io.github.inq_o.phonedoctor.core.model.AlertStatus
import io.github.inq_o.phonedoctor.core.model.AppIdentity
import io.github.inq_o.phonedoctor.core.model.ScanResult
import io.github.inq_o.phonedoctor.core.model.ScanRun
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals

class FinishScanTest {
    private val alert = SymptomAlert(7, AppIdentity("com.ad", "00", 1), "초고속 클리너", AlertStatus.OPEN, null, "2026-11-24", 5, 1, 0, 0, "test", 0, 0)

    @Test
    fun notifiesOpenedCancelsClosedPrunesAndSyncsSenior() = runBlocking {
        val report = ScanReport(run(), opened = listOf(alert), closed = listOf(3L))
        val notified = mutableListOf<Long>()
        val cancelled = mutableListOf<Long>()
        var pruned = 0
        val synced = mutableListOf<Boolean>()
        finishScan(report, { notified += it.id }, { cancelled += it }, { pruned++ }, senior = true) { synced += it }
        assertEquals(listOf(7L), notified)
        assertEquals(listOf(3L), cancelled)
        assertEquals(1, pruned)
        // 새 요청이 열렸으니 재시도 대기를 풀고 바로 보낸다
        assertEquals(listOf(true), synced)
        // 가족과 연결하지 않은 폰은 동기화를 맡기지 않는다
        finishScan(report, {}, {}, {}, senior = false) { synced += it }
        assertEquals(listOf(true), synced)
    }

    @Test
    fun quietScanDoesNotRushFamilySync() = runBlocking {
        val synced = mutableListOf<Boolean>()
        finishScan(ScanReport(run(), opened = emptyList(), closed = emptyList()), {}, {}, {}, senior = true) { synced += it }
        // 새로 생긴 것이 없으면 급하지 않다 (재시도 대기를 검사마다 처음으로 돌리지 않게)
        assertEquals(listOf(false), synced)
    }

    private fun run() = ScanRun(
        id = 1, startedAt = 0, trigger = "app_function", result = ScanResult.OK, queryFrom = null, queryTo = null,
        eventCount = 0, transitionCount = 0, adCount = 0, holdCount = 0, storedCount = 0,
        segmentId = 1, segmentKind = "install", quarantined = false, durationMs = 0, note = "",
    )
}
