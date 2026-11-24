package io.github.inq_o.phonedoctor.sync.work

import io.github.inq_o.phonedoctor.core.model.AlertFamilyState
import io.github.inq_o.phonedoctor.core.model.AlertOutcome
import io.github.inq_o.phonedoctor.core.model.AlertStatus
import io.github.inq_o.phonedoctor.core.model.AppIdentity
import io.github.inq_o.phonedoctor.core.model.FamilyDecision
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeclineNudgeTest {
    private fun alert(status: AlertStatus, outcome: AlertOutcome?, decision: FamilyDecision? = FamilyDecision.RECOMMEND_UNINSTALL) = SymptomAlert(
        1, AppIdentity("com.ad", "00", 1), "초고속 클리너", status, outcome, "2026-11-23", 5, 1, 0, 0, "test", 0, 0,
        family = AlertFamilyState(decision, "민지", null, closed = false),
    )

    private val notified = mutableListOf<SymptomAlert>()

    private fun run(current: SymptomAlert?, installed: Boolean?, after: SymptomAlert? = current) = runBlocking {
        var reads = 0
        nudgeIfStillDeclined(
            alert = { if (reads++ == 0) current else after },
            stillInstalled = { installed },
            notify = { notified += it },
        )
    }

    @Test
    fun nudgesOnceWhenStillDeclinedAndInstalled() {
        assertTrue(run(alert(AlertStatus.RESOLVED, AlertOutcome.DECLINED), installed = true))
        assertEquals(1, notified.size)
    }

    @Test
    fun skipsWhenRemovedReopenedOrUnknown() {
        assertFalse(run(alert(AlertStatus.RESOLVED, AlertOutcome.DECLINED), installed = false)) // 그 사이 지움
        assertFalse(run(alert(AlertStatus.RESOLVED, AlertOutcome.DECLINED), installed = null)) // 확인 못 함
        assertFalse(run(alert(AlertStatus.OPEN, AlertOutcome.DECLINED), installed = true)) // 근거가 쌓여 다시 열림
        assertFalse(run(alert(AlertStatus.RESOLVED, AlertOutcome.UNINSTALLED), installed = true))
        assertFalse(run(null, installed = true))
        // 연결을 끊었거나 모든 데이터를 지워 가족 답이 지워졌으면 알리지 않는다
        assertFalse(run(alert(AlertStatus.RESOLVED, AlertOutcome.DECLINED, decision = null), installed = true))
        // 설치 조회가 결과를 바꿨으면(다른 길로 지움 → already_removed) 알리지 않는다
        assertFalse(run(alert(AlertStatus.RESOLVED, AlertOutcome.DECLINED), installed = true, after = alert(AlertStatus.RESOLVED, AlertOutcome.ALREADY_REMOVED)))
        assertTrue(notified.isEmpty())
    }
}
