package io.github.inq_o.phonedoctor.core.data

import io.github.inq_o.phonedoctor.core.model.AlertOutcome
import io.github.inq_o.phonedoctor.core.model.AlertStatus

/** 하루 근거 한 줄 */
data class DayEvidence(val date: String, val count: Int, val firstAt: Long, val lastAt: Long)

/** 이미 있는 확인 요청의 판단에 필요한 부분 */
data class ExistingAlert(val status: AlertStatus, val outcome: AlertOutcome?, val date: String, val openedDate: String, val updatedAt: Long)

sealed interface AlertDecision {
    /** 기준을 넘지 않았거나 다시 알릴 조건이 아님 */
    data object None : AlertDecision

    /** 새 요청을 열거나 닫힌 요청을 다시 연다. 시니어에게 알린다 */
    data class Open(val evidence: DayEvidence, val days: Int) : AlertDecision

    /** 열린 요청의 근거만 최신 값으로 바꾼다. 알림은 없다 (FR-023) */
    data class Update(val evidence: DayEvidence, val days: Int) : AlertDecision
}

/** 증상 기준과 확인 요청 규칙 (FR-010·FR-023) */
object SymptomPolicy {
    /**
     * 하루에 이 횟수 이상 앱 밖 광고로 세면 확인 요청을 연다.
     * 개발 자료로 정하기 전의 임시값이다. 평가 전에 규칙과 함께 동결한다 (plan.md 증상 감지 설계).
     */
    const val DAILY_THRESHOLD = 3

    /** 광고로 센 전환의 보관 기간 (data-model.md) */
    const val RETENTION_DAYS = 30L

    /** 판정기·규칙 판. 근거와 함께 남긴다 */
    const val DETECTOR_VERSION = "usage-fx0-draft"

    /**
     * [days]는 날짜 오름차순의 하루 근거. 열린 요청이 있으면 그 요청이 열린 날부터, 없으면 이번에 새로 센 날부터다.
     * 닫힌 요청은 닫힌 뒤 새 근거가 생겼을 때만 다시 연다. 시니어가 취소한 요청은 다른 날에 다시 넘었을 때만 연다(하루 뒤 재안내).
     */
    fun decide(existing: ExistingAlert?, days: List<DayEvidence>, threshold: Int = DAILY_THRESHOLD): AlertDecision {
        val over = days.filter { it.count >= threshold }
        val latest = over.lastOrNull() ?: return AlertDecision.None
        if (existing == null) return AlertDecision.Open(latest, 1)
        if (existing.status == AlertStatus.OPEN) {
            return AlertDecision.Update(latest, over.count { it.date >= existing.openedDate })
        }
        val newEvidence = latest.lastAt > existing.updatedAt
        val anotherDay = latest.date > existing.date
        return if (newEvidence && (existing.outcome != AlertOutcome.DECLINED || anotherDay)) {
            AlertDecision.Open(latest, 1)
        } else {
            AlertDecision.None
        }
    }
}
