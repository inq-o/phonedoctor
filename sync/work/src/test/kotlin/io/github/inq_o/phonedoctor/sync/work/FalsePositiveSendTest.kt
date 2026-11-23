package io.github.inq_o.phonedoctor.sync.work

import io.github.inq_o.phonedoctor.core.data.FalsePositive
import io.github.inq_o.phonedoctor.core.network.ApiException
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertSource
import io.github.inq_o.phonedoctor.core.network.model.NetworkFalsePositiveReport
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FalsePositiveSendTest {
    private val report = NetworkFalsePositiveReport(NetworkAlertSource.SYMPTOM, "com.ad", 7, "ab".repeat(32), detectorVersion = "v1")
    private val sent = mutableListOf<NetworkFalsePositiveReport>()

    private fun run(open: Boolean = true, attempts: Int = 0, fail: Exception? = null) = runBlocking {
        sendFalsePositive(report, attempts, gate = { block -> if (open) block() else Unit; open }) {
            fail?.let { throw it }
            sent += it
        }
    }

    @Test
    fun sendsWhenTheGateAllows() {
        assertEquals(SendOutcome.SENT, run())
        assertEquals(listOf(report), sent)
    }

    @Test
    fun closedGateDropsWithoutSending() {
        // 끔·지우는 중·세대 바뀜·마감 지남은 FalsePositiveGate가 판단 (FalsePositiveGateTest)
        assertEquals(SendOutcome.DROPPED, run(open = false))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun retriesOnlyTransientFailures() {
        assertEquals(SendOutcome.RETRY, run(fail = IOException()))
        assertEquals(SendOutcome.RETRY, run(fail = ApiException(503, null, null, null, null, null)))
        assertEquals(SendOutcome.RETRY, run(fail = ApiException(429, null, null, null, null, null)))
        assertEquals(SendOutcome.RETRY, run(fail = ApiException(401, "app-check-invalid", null, null, null, null)))
        assertEquals(SendOutcome.FAILED, run(fail = ApiException(400, "bad-request", null, null, null, null)))
        assertEquals(SendOutcome.FAILED, run(attempts = FalsePositiveWorker.MAX_ATTEMPTS))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun dataRoundTrip() {
        val scan = FalsePositive(true, "com.ad", 7, "ab".repeat(32), "2026.11.1", 0.7, listOf("SIDELOADED"), null)
        val back = scan.toData(generation = 3, deadline = 5).toReport()!!
        assertEquals(NetworkAlertSource.INSTALL_SCAN, back.source)
        assertEquals(0.7, back.score)
        assertEquals(listOf("SIDELOADED"), back.reasonCodes)
        val data = scan.toData(generation = 3, deadline = 5)
        assertEquals(5, data.getLong(FalsePositiveWorker.KEY_DEADLINE, 0))
        assertEquals(3, data.getLong(FalsePositiveWorker.KEY_GENERATION, -1))
        val symptom = FalsePositive(false, "com.ad", 7, "ab".repeat(32), null, null, null, "v1").toData(0, 5).toReport()!!
        assertNull(symptom.score)
        assertNull(symptom.reasonCodes)
        assertEquals("v1", symptom.detectorVersion)
    }
}
