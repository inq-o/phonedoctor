package io.github.inq_o.phonedoctor.core.data

import io.github.inq_o.phonedoctor.core.model.AlertOutcome
import io.github.inq_o.phonedoctor.core.model.AlertStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class SymptomPolicyTest {
    private fun day(date: String, count: Int, lastAt: Long = 1_000) = DayEvidence(date, count, 100, lastAt)

    @Test
    fun belowThreshold_noAlert() {
        assertEquals(AlertDecision.None, SymptomPolicy.decide(null, listOf(day("2026-11-11", 2)), threshold = 3))
    }

    @Test
    fun firstCrossing_opens() {
        val d = day("2026-11-11", 3)
        assertEquals(AlertDecision.Open(d, 1), SymptomPolicy.decide(null, listOf(d), threshold = 3))
    }

    @Test
    fun openAlert_updatesEvidenceAndCountsDaysOverThreshold() {
        val open = ExistingAlert(AlertStatus.OPEN, null, "2026-11-10", "2026-11-10", 500)
        val days = listOf(day("2026-11-10", 4), day("2026-11-11", 1), day("2026-11-12", 5))
        assertEquals(AlertDecision.Update(days[2], 2), SymptomPolicy.decide(open, days, threshold = 3))
    }

    @Test
    fun openAlert_keepsLatestDayOverThreshold() {
        // 오늘은 아직 기준 아래면 근거는 기준을 넘은 마지막 날 그대로
        val open = ExistingAlert(AlertStatus.OPEN, null, "2026-11-10", "2026-11-10", 500)
        val days = listOf(day("2026-11-10", 4), day("2026-11-11", 1))
        assertEquals(AlertDecision.Update(days[0], 1), SymptomPolicy.decide(open, days, threshold = 3))
    }

    @Test
    fun declined_sameDay_doesNotReopen() {
        val declined = ExistingAlert(AlertStatus.RESOLVED, AlertOutcome.DECLINED, "2026-11-11", "2026-11-11", 2_000)
        assertEquals(AlertDecision.None, SymptomPolicy.decide(declined, listOf(day("2026-11-11", 9, lastAt = 3_000)), threshold = 3))
    }

    @Test
    fun declined_anotherDay_reopens() {
        val declined = ExistingAlert(AlertStatus.RESOLVED, AlertOutcome.DECLINED, "2026-11-11", "2026-11-11", 2_000)
        val d = day("2026-11-12", 3, lastAt = 9_000)
        assertEquals(AlertDecision.Open(d, 1), SymptomPolicy.decide(declined, listOf(d), threshold = 3))
    }

    @Test
    fun resolved_withoutNewEvidence_staysClosed() {
        val removed = ExistingAlert(AlertStatus.RESOLVED, AlertOutcome.UNINSTALLED, "2026-11-11", "2026-11-11", 5_000)
        assertEquals(AlertDecision.None, SymptomPolicy.decide(removed, listOf(day("2026-11-11", 5, lastAt = 4_000)), threshold = 3))
    }

    @Test
    fun reinstalledSameVersion_newEvidence_reopens() {
        val removed = ExistingAlert(AlertStatus.RESOLVED, AlertOutcome.UNINSTALLED, "2026-11-11", "2026-11-11", 5_000)
        val d = day("2026-11-11", 6, lastAt = 8_000)
        assertEquals(AlertDecision.Open(d, 1), SymptomPolicy.decide(removed, listOf(d), threshold = 3))
    }
}
