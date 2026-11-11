package io.github.inq_o.phonedoctor.core.data

/** 증상 감지 검사 예약. 구현은 sync:work (WorkManager) */
interface ScanScheduler {
    /** 주기 검사를 건다. 이미 걸려 있으면 그대로 둔다 */
    fun ensurePeriodic()

    /** 지금 한 번 검사한다 (앱을 열었을 때, 시니어가 버튼을 눌렀을 때) */
    fun scanNow(trigger: String)
}
