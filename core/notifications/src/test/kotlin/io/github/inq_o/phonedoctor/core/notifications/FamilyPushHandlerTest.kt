package io.github.inq_o.phonedoctor.core.notifications

import io.github.inq_o.phonedoctor.core.model.FamilyDecision
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
    fun guardianLinkMessagesUseSeniorName() {
        assertTrue(handler.handle(mapOf("v" to "1", "type" to "LINK_ACTIVATED", "linkId" to "l1", "protectedName" to "엄마")))
        // 시니어 폰의 검사 보고(T055a)가 붙기 전에는 연락 끊김을 띄우지 않는다
        assertFalse(handler.handle(mapOf("v" to "1", "type" to "CONTACT_LOST", "linkId" to "l2", "protectedName" to "아빠")))
        assertEquals(listOf("activated:l1:엄마"), notifier.calls)
    }

    @Test
    fun linkRevokedUsesSenderNameEitherKey() {
        handler.handle(mapOf("v" to "1", "type" to "LINK_REVOKED", "linkId" to "l1", "guardianName" to "민지"))
        handler.handle(mapOf("v" to "1", "type" to "LINK_REVOKED", "linkId" to "l2", "protectedName" to "엄마"))
        handler.handle(mapOf("v" to "1", "type" to "LINK_REVOKED", "linkId" to "l3"))
        assertEquals(listOf("revoked:l1:민지:null", "revoked:l2:null:엄마", "revoked:l3:null:null"), notifier.calls)
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
        override fun notifyDecision(alert: SymptomAlert, decision: FamilyDecision, guardianName: String?) = Unit
        override fun notifyLinkRequested(linkId: String, guardianName: String?) {
            calls += "requested:$linkId:$guardianName"
        }
        override fun notifyLinkActivated(linkId: String, seniorName: String?) {
            calls += "activated:$linkId:$seniorName"
        }
        override fun notifyLinkRevoked(linkId: String, guardianName: String?, seniorName: String?) {
            calls += "revoked:$linkId:$guardianName:$seniorName"
        }
        override fun cancelLink(linkId: String) = Unit
    }
}
