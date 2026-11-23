package io.github.inq_o.phonedoctor.core.notifications

import java.time.LocalDate

/** 보호자에게 오는 요청 알림 종류 (push-messages.md §2) */
enum class GuardianAlertKind {
    /** ALERT_CREATED: 새 요청 (승인 필요) */
    CREATED,

    /** ALERT_REMINDER: 24시간 답이 없어 한 번 더 */
    REMINDER,

    /** ALERT_NOTICE: 허용한 앱이 다시 기준을 넘음 (알림 전용) */
    NOTICE,
}

/**
 * 보호자 요청 알림 하나의 내용. 페이로드에는 ID와 짧은 표시 문구만 있다 (잠금화면 노출 최소화).
 * [symptomDate]가 오늘이 아니면 "오늘" 대신 날짜를 쓴다 (자정 넘어 도착)
 */
data class GuardianAlertMessage(
    val alertId: String,
    val kind: GuardianAlertKind,
    val seniorName: String?,
    val appLabel: String?,
    val installScan: Boolean,
    val symptomCount: Int?,
    val symptomDate: LocalDate?,
)
