package io.github.inq_o.phonedoctor.core.notifications

import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyPushHandlerTest {
    private val notifier = RecordingNotifier()
    private val handler = FamilyPushHandler(notifier)

    @Test
    fun linkRequestedShowsRequestNotification() {
        assertTrue(handler.handle(mapOf("v" to "1", "type" to "LINK_REQUESTED", "linkId" to "l1", "guardianName" to "민지")))
        assertEquals(listOf("requested:l1:민지"), notifier.calls)
    }

    @Test
    fun linkRevokedUsesSenderNameEitherKey() {
        handler.handle(mapOf("v" to "1", "type" to "LINK_REVOKED", "linkId" to "l1", "guardianName" to "민지"))
        handler.handle(mapOf("v" to "1", "type" to "LINK_REVOKED", "linkId" to "l2", "protectedName" to "엄마"))
        handler.handle(mapOf("v" to "1", "type" to "LINK_REVOKED", "linkId" to "l3"))
        assertEquals(listOf("revoked:l1:민지", "revoked:l2:엄마", "revoked:l3:null"), notifier.calls)
    }

    @Test
    fun ignoresUnknownVersionTypeOrMissingLink() {
        assertFalse(handler.handle(mapOf("v" to "2", "type" to "LINK_REQUESTED", "linkId" to "l1")))
        assertFalse(handler.handle(mapOf("type" to "LINK_REQUESTED", "linkId" to "l1")))
        assertFalse(handler.handle(mapOf("v" to "1", "type" to "LINK_REQUESTED")))
        assertFalse(handler.handle(mapOf("v" to "1", "type" to "LINK_REQUESTED", "linkId" to " ")))
        assertFalse(handler.handle(mapOf("v" to "1", "type" to "ALERT_UPDATED", "alertId" to "a1", "linkId" to "l1")))
        assertTrue(notifier.calls.isEmpty())
    }

    private class RecordingNotifier : Notifier {
        val calls = mutableListOf<String>()
        override fun canNotify() = true
        override fun notifyAlert(alert: SymptomAlert) = Unit
        override fun cancelAlert(alertId: Long) = Unit
        override fun notifyLinkRequested(linkId: String, guardianName: String?) {
            calls += "requested:$linkId:$guardianName"
        }
        override fun notifyLinkRevoked(linkId: String, name: String?) {
            calls += "revoked:$linkId:$name"
        }
        override fun cancelLink(linkId: String) = Unit
    }
}
