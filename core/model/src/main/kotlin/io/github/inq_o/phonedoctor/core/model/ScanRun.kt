package io.github.inq_o.phonedoctor.core.model

enum class ScanResult {
    /** 사용 기록을 읽고 판정까지 끝남 */
    OK,

    /** 사용 기록 접근이 꺼져 있음 */
    NO_PERMISSION,

    /** 사용자 잠금 해제 전이라 조회하지 않음 */
    LOCKED,

    /** 조회가 null (이벤트 0건과 다름) */
    QUERY_NULL,

    /** 시계 변경으로 이번 조회분을 버림 */
    CLOCK_DISCARDED,

    ERROR,
}

/** 검사 1회 기록. "이벤트 0건"과 "검사하지 못함"을 구분해 남긴다 */
data class ScanRun(
    val id: Long,
    val startedAt: Long,
    val trigger: String,
    val result: ScanResult,
    val queryFrom: Long?,
    val queryTo: Long?,
    /** 조회가 돌려준 이벤트 수 (판정기가 쓰는 종류만) */
    val eventCount: Int,
    /** 이번에 확정한 화면 전환 수 */
    val transitionCount: Int,
    val adCount: Int,
    val holdCount: Int,
    /** 광고로 세어 저장한 수 (앱 정보가 확인된 것만) */
    val storedCount: Int,
    val segmentId: Int,
    val segmentKind: String,
    /** 끝을 모르는 버릴 범위가 열려 있어 판정을 멈춘 상태 */
    val quarantined: Boolean,
    val durationMs: Long,
    val note: String,
)
