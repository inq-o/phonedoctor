package io.github.inq_o.phonedoctor.core.notifications

import java.time.LocalDate
import javax.inject.Inject

/**
 * 서버 푸시(FCM data 메시지, push-messages.md)를 알림으로 바꾼다. 네트워크는 부르지 않는다 (상세는 알림을 눌러 연 화면이 불러옴).
 * 연결 메시지(LINK_*·CONTACT_LOST)와 보호자 요청 메시지(ALERT_*). 시니어의 DECISION_MADE는 기기 DB를 고쳐야 해서 앱이 따로 처리한다
 */
class FamilyPushHandler @Inject constructor(
    private val notifier: Notifier,
    private val requestChanges: RequestChanges,
) {
    /** 처리했으면 true. 모르는 종류·형식 버전은 무시한다 */
    fun handle(data: Map<String, String>): Boolean {
        if (data["v"] != VERSION) return false
        val type = data["type"] ?: return false
        if (type.startsWith("ALERT_")) return handleAlert(type, data)
        val linkId = data["linkId"]?.takeIf { it.isNotBlank() } ?: return false
        return when (type) {
            "LINK_REQUESTED" -> {
                notifier.notifyLinkRequested(linkId, data["guardianName"])
                true
            }
            "LINK_ACTIVATED" -> {
                notifier.notifyLinkActivated(linkId, data["protectedName"])
                true
            }
            // 이름 키는 보낸 쪽 역할을 따른다: 보호자가 끊으면 guardianName, 시니어가 끊으면 protectedName
            "LINK_REVOKED" -> {
                notifier.notifyLinkRevoked(linkId, data["guardianName"], data["protectedName"])
                true
            }
            // 시니어 폰의 검사 보고가 48시간 없음 (F6)
            "CONTACT_LOST" -> {
                notifier.notifyContactLost(linkId, data["protectedName"])
                true
            }
            else -> false
        }
    }

    private fun handleAlert(type: String, data: Map<String, String>): Boolean {
        val alertId = data["alertId"]?.takeIf { it.isNotBlank() } ?: return false
        if (type !in KNOWN_ALERT_TYPES) return false
        // 열려 있는 요청 화면이 다시 읽게 (다른 보호자가 먼저 답함·결과·근거 갱신)
        requestChanges.changed(alertId)
        val kind = when (type) {
            "ALERT_CREATED" -> GuardianAlertKind.CREATED
            "ALERT_REMINDER" -> GuardianAlertKind.REMINDER
            "ALERT_NOTICE" -> GuardianAlertKind.NOTICE
            "ALERT_RESOLVED" -> {
                notifier.notifyAlertResolved(alertId, data["outcome"], data["protectedName"], data["appLabel"])
                return true
            }
            // 다른 보호자가 먼저 답함·근거 갱신: 알림 없이 목록만 바뀐다 (화면이 다시 불러옴)
            "ALERT_UPDATED" -> return true
            else -> return false
        }
        notifier.notifyGuardianAlert(
            GuardianAlertMessage(
                alertId = alertId,
                kind = kind,
                seniorName = data["protectedName"],
                appLabel = data["appLabel"],
                installScan = data["source"] == "install_scan",
                symptomCount = data["symptomCount"]?.toIntOrNull(),
                symptomDate = data["symptomDate"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            ),
        )
        return true
    }

    private companion object {
        const val VERSION = "1"
        val KNOWN_ALERT_TYPES = setOf("ALERT_CREATED", "ALERT_REMINDER", "ALERT_NOTICE", "ALERT_RESOLVED", "ALERT_UPDATED")
    }
}
