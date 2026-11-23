package io.github.inq_o.phonedoctor.core.notifications

import javax.inject.Inject

/**
 * 서버 푸시(FCM data 메시지, push-messages.md)를 알림으로 바꾼다. 네트워크는 부르지 않는다 (상세는 알림을 눌러 연 화면이 불러옴).
 * 지금은 연결 메시지(LINK_*·CONTACT_LOST)만. 요청(ALERT_*·DECISION_MADE)은 해당 화면과 함께 붙인다
 */
class FamilyPushHandler @Inject constructor(private val notifier: Notifier) {
    /** 처리했으면 true. 모르는 종류·형식 버전은 무시한다 */
    fun handle(data: Map<String, String>): Boolean {
        if (data["v"] != VERSION) return false
        val linkId = data["linkId"]?.takeIf { it.isNotBlank() } ?: return false
        return when (data["type"]) {
            "LINK_REQUESTED" -> {
                notifier.notifyLinkRequested(linkId, data["guardianName"])
                true
            }
            "LINK_ACTIVATED" -> {
                notifier.notifyLinkActivated(linkId, data["protectedName"])
                true
            }
            "CONTACT_LOST" -> {
                notifier.notifyContactLost(linkId, data["protectedName"])
                true
            }
            // 이름 키는 보낸 쪽 역할을 따른다: 보호자가 끊으면 guardianName, 시니어가 끊으면 protectedName
            "LINK_REVOKED" -> {
                notifier.notifyLinkRevoked(linkId, data["guardianName"], data["protectedName"])
                true
            }
            else -> false
        }
    }

    private companion object {
        const val VERSION = "1"
    }
}
