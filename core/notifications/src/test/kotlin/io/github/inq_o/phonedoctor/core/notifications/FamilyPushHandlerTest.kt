package io.github.inq_o.phonedoctor.core.notifications

import io.github.inq_o.phonedoctor.core.model.FamilyDecision
import io.github.inq_o.phonedoctor.core.model.PhoneName
import io.github.inq_o.phonedoctor.core.model.SymptomAlert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FamilyPushHandlerTest {
    private val notifier = RecordingNotifier()
    private val changes = RequestChanges()
    // 보호자가 이 폰에 l1만 "엄마"로 정해 둠 (ux-flows §2)
    private val chosen = mapOf("l1" to "엄마")
    private val handler = FamilyPushHandler(notifier, changes) { chosen[it] }

    @Test
    fun linkRequestedShowsRequestNotification() {
        assertTrue(handler.handle(mapOf("v" to "1", "type" to "LINK_REQUESTED", "linkId" to "l1", "guardianName" to "민지")))
        assertEquals(listOf("requested:l1:민지"), notifier.calls)
    }

    @Test
    fun guardianLinkMessagesUsePhoneName() {
        // 보호자가 고른 이름이 상대가 정한 이름보다 먼저
        assertTrue(handler.handle(mapOf("v" to "1", "type" to "LINK_ACTIVATED", "linkId" to "l1", "protectedName" to "김영희")))
        // 시니어 폰이 6시간마다 검사 보고를 보내므로(T055a) 연락 끊김도 알린다
        assertTrue(handler.handle(mapOf("v" to "1", "type" to "CONTACT_LOST", "linkId" to "l2", "protectedName" to "김철수")))
        // 이름이 둘 다 없으면 역할 이름을 대신 넣지 않는다
        assertTrue(handler.handle(mapOf("v" to "1", "type" to "CONTACT_LOST", "linkId" to "l3", "protectedName" to "")))
        assertEquals(listOf("activated:l1:Chosen(name=엄마)", "lost:l2:Own(name=김철수)", "lost:l3:Unnamed"), notifier.calls)
    }

    @Test
    fun guardianAlertMessagesBecomeRequestNotifications() {
        val base = mapOf("v" to "1", "alertId" to "a1", "linkId" to "l1", "protectedName" to "김영희", "appLabel" to "초고속 클리너")
        val mom = PhoneName.Chosen("엄마")
        assertTrue(handler.handle(base + mapOf("type" to "ALERT_CREATED", "source" to "symptom", "symptomCount" to "12", "symptomDate" to "2026-10-23")))
        assertTrue(handler.handle(base + mapOf("type" to "ALERT_REMINDER", "source" to "install_scan")))
        assertTrue(handler.handle(base + mapOf("type" to "ALERT_NOTICE", "source" to "symptom", "symptomCount" to "x")))
        assertEquals(
            listOf(
                GuardianAlertMessage("a1", GuardianAlertKind.CREATED, mom, "초고속 클리너", false, 12, java.time.LocalDate.of(2026, 10, 23)),
                GuardianAlertMessage("a1", GuardianAlertKind.REMINDER, mom, "초고속 클리너", true, null, null),
                GuardianAlertMessage("a1", GuardianAlertKind.NOTICE, mom, "초고속 클리너", false, null, null),
            ),
            notifier.messages,
        )
        // 결과는 결과 알림, 갱신은 알림 없이 처리한 것으로
        assertTrue(handler.handle(base + mapOf("type" to "ALERT_RESOLVED", "outcome" to "uninstalled")))
        assertTrue(handler.handle(mapOf("v" to "1", "type" to "ALERT_UPDATED", "alertId" to "a1")))
        assertEquals(listOf("resolved:a1:uninstalled:Chosen(name=엄마):초고속 클리너"), notifier.calls)
        // linkId가 없는 옛 서버 푸시는 상대가 정한 이름
        notifier.messages.clear()
        assertTrue(handler.handle(base - "linkId" + mapOf("type" to "ALERT_CREATED", "source" to "install_scan")))
        assertEquals(PhoneName.Own("김영희"), notifier.messages.single().phone)
        // 요청 ID가 없으면 무시
        assertFalse(handler.handle(mapOf("v" to "1", "type" to "ALERT_CREATED")))
    }

    @Test
    fun linkRevokedUsesSenderNameEitherKey() {
        handler.handle(mapOf("v" to "1", "type" to "LINK_REVOKED", "linkId" to "l1", "guardianName" to "민지"))
        handler.handle(mapOf("v" to "1", "type" to "LINK_REVOKED", "linkId" to "l2", "protectedName" to "김영희"))
        handler.handle(mapOf("v" to "1", "type" to "LINK_REVOKED", "linkId" to "l3"))
        assertEquals(listOf("revoked:l1:민지:null", "revoked:l2:null:Own(name=김영희)", "revoked:l3:null:null"), notifier.calls)
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
        override fun notifyLinkActivated(linkId: String, phone: io.github.inq_o.phonedoctor.core.model.PhoneName) {
            calls += "activated:$linkId:$phone"
        }
        override fun notifyLinkRevoked(linkId: String, guardianName: String?, phone: io.github.inq_o.phonedoctor.core.model.PhoneName?) {
            calls += "revoked:$linkId:$guardianName:$phone"
        }
        override fun cancelLink(linkId: String) = Unit
        val messages = mutableListOf<GuardianAlertMessage>()
        override fun notifyContactLost(linkId: String, phone: io.github.inq_o.phonedoctor.core.model.PhoneName) {
            calls += "lost:$linkId:$phone"
        }
        override fun notifyGuardianAlert(message: GuardianAlertMessage) {
            messages += message
        }
        override fun notifyAlertResolved(alertId: String, outcome: String?, phone: io.github.inq_o.phonedoctor.core.model.PhoneName, appLabel: String?) {
            calls += "resolved:$alertId:$outcome:$phone:$appLabel"
        }
        override fun cancelGuardianAlert(alertId: String) = Unit
        override fun notifySiteAds(alert: io.github.inq_o.phonedoctor.core.model.SiteAlert, stillOn: Boolean) = Unit
        override fun cancelSite(site: String) = Unit
    }
}
