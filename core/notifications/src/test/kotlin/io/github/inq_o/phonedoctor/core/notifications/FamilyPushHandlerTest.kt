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
        // 시니어 폰이 6시간마다 검사 보고를 보내므로(T055a) 연락 끊김도 알린다
        assertTrue(handler.handle(mapOf("v" to "1", "type" to "CONTACT_LOST", "linkId" to "l2", "protectedName" to "아빠")))
        assertEquals(listOf("activated:l1:엄마", "lost:l2:아빠"), notifier.calls)
    }

    @Test
    fun guardianAlertMessagesBecomeRequestNotifications() {
        val base = mapOf("v" to "1", "alertId" to "a1", "protectedName" to "엄마", "appLabel" to "초고속 클리너")
        assertTrue(handler.handle(base + mapOf("type" to "ALERT_CREATED", "source" to "symptom", "symptomCount" to "12", "symptomDate" to "2026-10-23")))
        assertTrue(handler.handle(base + mapOf("type" to "ALERT_REMINDER", "source" to "install_scan")))
        assertTrue(handler.handle(base + mapOf("type" to "ALERT_NOTICE", "source" to "symptom", "symptomCount" to "x")))
        assertEquals(
            listOf(
                GuardianAlertMessage("a1", GuardianAlertKind.CREATED, "엄마", "초고속 클리너", false, 12, java.time.LocalDate.of(2026, 10, 23)),
                GuardianAlertMessage("a1", GuardianAlertKind.REMINDER, "엄마", "초고속 클리너", true, null, null),
                GuardianAlertMessage("a1", GuardianAlertKind.NOTICE, "엄마", "초고속 클리너", false, null, null),
            ),
            notifier.messages,
        )
        // 결과는 결과 알림, 갱신은 알림 없이 처리한 것으로
        assertTrue(handler.handle(base + mapOf("type" to "ALERT_RESOLVED", "outcome" to "uninstalled")))
        assertTrue(handler.handle(mapOf("v" to "1", "type" to "ALERT_UPDATED", "alertId" to "a1")))
        assertEquals(listOf("resolved:a1:uninstalled:엄마:초고속 클리너"), notifier.calls)
        // 요청 ID가 없으면 무시
        assertFalse(handler.handle(mapOf("v" to "1", "type" to "ALERT_CREATED")))
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
        assertFalse(handler.handle(mapOf("v" to "1", "type" to "MODEL_UPDATED", "linkId" to "l1")))
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
        val messages = mutableListOf<GuardianAlertMessage>()
        override fun notifyContactLost(linkId: String, seniorName: String?) {
            calls += "lost:$linkId:$seniorName"
        }
        override fun notifyGuardianAlert(message: GuardianAlertMessage) {
            messages += message
        }
        override fun notifyAlertResolved(alertId: String, outcome: String?, seniorName: String?, appLabel: String?) {
            calls += "resolved:$alertId:$outcome:$seniorName:$appLabel"
        }
        override fun cancelGuardianAlert(alertId: String) = Unit
    }
}
