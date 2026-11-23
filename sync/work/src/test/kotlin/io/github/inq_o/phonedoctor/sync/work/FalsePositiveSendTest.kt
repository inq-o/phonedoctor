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

    private fun run(
        enabled: Boolean = true,
        now: Long = 100,
        deadline: Long = 200,
        attempts: Int = 0,
        fail: Exception? = null,
    ) = runBlocking {
        sendFalsePositive(report, { enabled }, now, deadline, attempts) {
            fail?.let { throw it }
            sent += it
        }
    }

    @Test
    fun sendsWhenOnAndInTime() {
        assertEquals(SendOutcome.SENT, run())
        assertEquals(listOf(report), sent)
    }

    @Test
    fun turnedOffAfterSchedulingIsNotSent() {
        assertEquals(SendOutcome.DROPPED, run(enabled = false))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun pastTheDeadlineIsNotSent() {
        assertEquals(SendOutcome.SENT, run(now = 200, deadline = 200))
        sent.clear()
        assertEquals(SendOutcome.DROPPED, run(now = 201, deadline = 200))
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
    }

    @Test
    fun dataRoundTrip() {
        val scan = FalsePositive(true, "com.ad", 7, "ab".repeat(32), "2026.11.1", 0.7, listOf("SIDELOADED"), null)
        val back = scan.toData(deadline = 5).toReport()!!
        assertEquals(NetworkAlertSource.INSTALL_SCAN, back.source)
        assertEquals(0.7, back.score)
        assertEquals(listOf("SIDELOADED"), back.reasonCodes)
        assertEquals(5, scan.toData(deadline = 5).getLong(FalsePositiveWorker.KEY_DEADLINE, 0))
        val symptom = FalsePositive(false, "com.ad", 7, "ab".repeat(32), null, null, null, "v1").toData(5).toReport()!!
        assertNull(symptom.score)
        assertNull(symptom.reasonCodes)
        assertEquals("v1", symptom.detectorVersion)
    }
}
