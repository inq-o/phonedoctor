package io.github.inq_o.phonedoctor.core.model

/** 앱 식별 단위. 하루 횟수·허용 목록·요청은 이 조합으로 묶는다 (data-model.md) */
data class AppIdentity(val packageName: String, val signingCertSha256: String, val versionCode: Long)

enum class AlertStatus { OPEN, RESOLVED }

/** 삭제 시도의 결과. 확인하지 못한 것을 삭제 성공으로 세지 않는다 */
enum class AlertOutcome {
    /** 시스템 삭제가 끝났고 다시 조회해도 앱이 없음 */
    UNINSTALLED,

    /** 시니어가 시스템 확인창에서 취소 */
    DECLINED,

    /** 삭제를 요청하기 전에 이미 없어짐 */
    ALREADY_REMOVED,

    /** 시스템이 삭제 실패를 돌려줌. 요청은 열린 채로 둔다 */
    FAILED,

    /** 앱이 업데이트돼 근거를 모은 판과 달라짐. 새 판은 새로 센다 */
    VERSION_CHANGED,
}

/** 앱별 하루 앱 밖 광고 추정 횟수 (검사 기록 화면용) */
data class DailyAppCount(val packageName: String, val label: String, val date: String, val count: Int)

/** 앱 밖 광고 기준을 넘은 앱 1개에 대한 확인 요청 (기기 안 사본) */
data class SymptomAlert(
    val id: Long,
    val app: AppIdentity,
    val label: String,
    val status: AlertStatus,
    val outcome: AlertOutcome?,
    /** 근거의 현지 날짜 (yyyy-MM-dd) */
    val date: String,
    /** 그 날짜의 앱 밖 광고 추정 횟수 */
    val count: Int,
    /** 요청이 열린 날부터 기준을 넘은 날 수 */
    val days: Int,
    val firstAt: Long,
    val lastAt: Long,
    val detectorVersion: String,
    val createdAt: Long,
    val updatedAt: Long,
)
