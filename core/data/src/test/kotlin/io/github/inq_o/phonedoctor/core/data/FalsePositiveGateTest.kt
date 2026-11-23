package io.github.inq_o.phonedoctor.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import io.github.inq_o.phonedoctor.core.datastore.UserPreferences
import io.github.inq_o.phonedoctor.core.network.ServerClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FalsePositiveGateTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val report = FalsePositive(false, "com.ad", 7, "ab".repeat(32), null, null, null, "v1")
    private val scheduled = mutableListOf<Triple<Long, Long, Long>>() // (세대, 마감, 늦춤)
    private var cancelled = 0
    private val hour = 3_600_000L
    private val now = 100 * hour

    private val serverClock = ServerClock()

    private fun TestScope.gate(): Pair<FalsePositiveGate, UserPreferences> {
        val prefs = UserPreferences(PreferenceDataStoreFactory.create(scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))) {
            tmp.newFile("g.preferences_pb").also { it.delete() }
        })
        val gate = FalsePositiveGate(prefs, object : FalsePositiveReporter {
            override fun schedule(id: String, report: FalsePositive, generation: Long, deadline: Long, delayMillis: Long) {
                scheduled += Triple(generation, deadline, delayMillis)
            }

            override fun cancelAll() {
                cancelled++
            }
        }, serverClock)
        gate.clock = { now }
        return gate to prefs
    }

    @Test
    fun schedulesOnceWithinTheWindow() = runTest {
        val (gate, _) = gate()
        assertTrue(gate.schedule("a1:symptom", report, 0, decidedAt = now))
        assertFalse(gate.schedule("a1:symptom", report, 0, decidedAt = now)) // 같은 요청·근거는 한 번
        val (generation, deadline, delay) = scheduled.single()
        assertEquals(0, generation)
        assertEquals(now + 24 * hour, deadline)
        assertTrue(delay in hour..(23 * hour))
    }

    @Test
    fun doesNotScheduleWhenItCannotWaitAnHourBeforeTheDeadline() = runTest {
        val (gate, _) = gate()
        assertFalse(gate.schedule("old", report, 0, decidedAt = now - 22 * hour)) // 남은 2시간 - 여유 1시간 = 1시간: 1시간 넘게 늦출 수 없음
        assertTrue(gate.schedule("ok", report, 0, decidedAt = now - 21 * hour))
        assertEquals(1, scheduled.size)
    }

    @Test
    fun invalidatingStopsEarlierGenerations() = runTest {
        val (gate, prefs) = gate()
        val before = gate.generation()
        gate.disable()
        assertEquals(1, cancelled)
        assertFalse(prefs.falsePositiveFeedback.first())
        assertEquals(before + 1, gate.generation()) // 끄기와 세대 올리기를 한 번에
        prefs.setFalsePositiveFeedback(true)
        assertFalse(gate.schedule("a1:symptom", report, before, now))
        var sent = 0
        assertFalse(gate.send(before, now + hour) { sent++ })
        assertTrue(gate.send(gate.generation(), now + hour) { sent++ })
        assertEquals(1, sent)
    }

    @Test
    fun deletionInvalidatesWithoutTurningOff() = runTest {
        val (gate, prefs) = gate()
        gate.invalidate()
        assertEquals(1, cancelled)
        assertEquals(1L, gate.generation())
        assertTrue(prefs.falsePositiveFeedback.first())
    }

    @Test
    fun deadlineFollowsTheServerClock() = runTest {
        val (gate, _) = gate()
        // 기기 시계가 서버보다 12시간 느림: 서버 시각 기준 답한 지 23시간이면 기기 시계로도 마감이 1시간 남은 것으로
        serverClock.record(serverMillis = now + 12 * hour, localMillis = now)
        assertFalse(gate.schedule("late", report, 0, decidedAt = now + 12 * hour - 23 * hour))
        assertTrue(gate.schedule("ok", report, 0, decidedAt = now + 12 * hour - 10 * hour))
        val (_, deadline, delay) = scheduled.single()
        assertEquals(now + 14 * hour, deadline) // 서버 기준 마감(답 + 24시간)을 기기 시계로
        assertTrue(delay in hour..(13 * hour))
        assertEquals(now + 12 * hour, gate.serverNow())
    }

    @Test
    fun sendChecksSettingDeletionAndDeadline() = runTest {
        val (gate, prefs) = gate()
        var sent = 0
        assertFalse(gate.send(0, now - 1) { sent++ }) // 마감 지남
        prefs.setFalsePositiveFeedback(false)
        assertFalse(gate.send(0, now + hour) { sent++ })
        prefs.setFalsePositiveFeedback(true)
        prefs.setDeletionPending(true)
        assertFalse(gate.send(0, now + hour) { sent++ })
        prefs.setDeletionPending(false)
        assertTrue(gate.send(0, now) { sent++ }) // 마감 시각 그 자체는 보냄
        assertEquals(1, sent)
    }
}
