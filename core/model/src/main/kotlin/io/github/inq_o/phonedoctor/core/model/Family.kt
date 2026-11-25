package io.github.inq_o.phonedoctor.core.model

import java.time.Instant

/** 이 기기의 역할 (ux-flows O1). 시니어 기기 = 서버의 `protected` */
enum class DeviceRole { SENIOR, GUARDIAN }

/** 시니어 기기가 받은 연결 코드 (O-S4). 10분 뒤 만료 */
data class PairingCode(val code: String, val qrPayload: String, val expiresAt: Instant)

enum class LinkStatus {
    /** 보호자가 코드를 넣음, 시니어 확인 전 (S3) */
    PENDING,
    ACTIVE,
    REVOKED,

    /** 앱이 모르는 상태 (서버가 먼저 더한 값) */
    UNKNOWN,
}

/** 시니어 기기와 보호자 기기의 연결 (data-model Link) */
data class FamilyLink(
    val linkId: String,
    val status: LinkStatus,
    val seniorName: String,
    val guardianName: String,
    val createdAt: Instant,
    val activatedAt: Instant?,
    /** 시니어 기기가 마지막으로 검사를 끝냈다고 보고한 시각 (F6) */
    val lastCheckedAt: Instant?,
    /** 시니어 기기가 보고한 보호 상태 (권한). 보고 전이면 null */
    val protection: ProtectionFlags? = null,
)

/** 시니어 폰의 보호 상태 (openapi ProtectionState). 꺼진 것이 있으면 보호자 홈에 알린다 */
data class ProtectionFlags(val notifications: Boolean, val usageAccess: Boolean, val notificationListener: Boolean)

enum class FamilyAlertStatus { OPEN, DECIDED, RESOLVED, EXPIRED, UNKNOWN }

/** 시니어 폰이 보고한 처리 결과 (openapi OutcomeType) */
enum class FamilyOutcome { UNINSTALLED, DECLINED, ALREADY_REMOVED, SUSPENDED, ALLOWED, UNKNOWN }

/** 증상 감지 근거 (openapi SymptomEvidence). [date]는 시니어 폰의 현지 날짜 */
data class FamilySymptom(val date: String, val count: Int, val days: Int, val firstAt: Instant, val lastAt: Instant, val detectorVersion: String)

/** 설치 시점 판정 (openapi Verdict). [reasons]는 근거 코드 (classifier.md §4), 앱이 모르는 코드도 그대로 */
data class FamilyVerdict(val level: String, val score: Double, val modelVersion: String, val reasons: List<String>)

/** 보호자의 응답 */
data class FamilyAnswer(val decision: FamilyDecision, val guardianName: String, val message: String?, val decidedAt: Instant)

/** 보호자가 보는 요청 하나 (openapi Alert). 시니어 폰에서 확인이 필요하다고 본 앱 */
data class FamilyAlert(
    val alertId: String,
    val seniorName: String,
    val status: FamilyAlertStatus,
    /** 보호자가 허용한 앱의 알림 전용 (FR-007). [괜찮은 앱이에요]가 없다 */
    val notice: Boolean,
    /** install_scan = 설치 시점 판정, symptom = 앱 밖 광고 */
    val fromInstallScan: Boolean,
    val appLabel: String,
    val packageName: String,
    val versionName: String?,
    val installSource: String?,
    val firstInstallTime: Instant?,
    val symptom: FamilySymptom?,
    val verdict: FamilyVerdict? = null,
    val answer: FamilyAnswer?,
    val outcome: FamilyOutcome?,
    val createdAt: Instant,
    val updatedAt: Instant,
    /** 이 보호자가 이 요청을 받은 연결 (openapi Alert.linkIds). 보호자가 고른 표시 이름을 찾을 때 쓴다 */
    val linkIds: List<String> = emptyList(),
) {
    /** 아직 아무도 답하지 않아 이 보호자가 답할 수 있음 */
    val answerable: Boolean get() = status == FamilyAlertStatus.OPEN && answer == null
}
