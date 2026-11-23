package io.github.inq_o.phonedoctor.core.network.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// specs/001-mvp/contracts/openapi.yaml components/schemas 를 그대로 옮긴 통신용 형태.
// 화면·DB는 core:model 형태를 쓰고, 이 형태는 core:data의 저장소에서만 바꾼다 (Now in Android의 network 모듈과 같은 구성).
// 시각은 RFC 3339 문자열 그대로 둔다(서버 Instant.toString). 해석은 쓰는 쪽에서

@Serializable
enum class NetworkDeviceRole {
    @SerialName("protected") PROTECTED,
    @SerialName("guardian") GUARDIAN,

    /** 앱이 모르는 값 (서버가 먼저 더한 값). 보내지 않는다 */
    @SerialName("_unknown") UNKNOWN,
}

@Serializable
data class NetworkDeviceRegistration(
    val role: NetworkDeviceRole,
    val fcmToken: String,
    val displayName: String? = null,
    val appVersionCode: Int,
    val sdkInt: Int,
    val manufacturer: String? = null,
    val locale: String? = null,
)

@Serializable
data class NetworkDeviceUpdate(
    val fcmToken: String? = null,
    val displayName: String? = null,
    val appVersionCode: Int? = null,
    val notifyCaution: Boolean? = null,
) {
    init {
        // 계약 minProperties: 1. 빈 본문은 서버가 400으로 거절하므로 만들 때 막는다
        require(fcmToken != null || displayName != null || appVersionCode != null || notifyCaution != null) { "바꿀 값이 없음" }
    }
}

@Serializable
data class NetworkProtectionState(
    val notifications: Boolean,
    val usageAccess: Boolean,
    val notificationListener: Boolean,
)

@Serializable
data class NetworkDeviceStatus(val checkedAt: String, val protection: NetworkProtectionState)

@Serializable
data class NetworkDevice(
    val deviceId: String,
    val role: NetworkDeviceRole = NetworkDeviceRole.UNKNOWN,
    val displayName: String,
    val notifyCaution: Boolean = false,
    val createdAt: String,
)

@Serializable
data class NetworkPairingCode(val code: String, val qrPayload: String, val expiresAt: String)

@Serializable
enum class NetworkLinkStatus {
    @SerialName("pending") PENDING,
    @SerialName("active") ACTIVE,
    @SerialName("revoked") REVOKED,

    /** 앱이 모르는 값 (서버가 먼저 더한 값). 보내지 않는다 */
    @SerialName("_unknown") UNKNOWN,
}

@Serializable
data class NetworkDeviceSummary(val deviceId: String, val displayName: String)

@Serializable
data class NetworkLink(
    val linkId: String,
    val status: NetworkLinkStatus = NetworkLinkStatus.UNKNOWN,
    val protectedDevice: NetworkDeviceSummary,
    val guardianDevice: NetworkDeviceSummary,
    val createdAt: String,
    val activatedAt: String? = null,
    val lastSeenAt: String? = null,
    val lastCheckedAt: String? = null,
    val protection: NetworkProtectionState? = null,
)

@Serializable
data class NetworkLinkList(val items: List<NetworkLink>)

@Serializable
enum class NetworkRiskLevel {
    SAFE, CAUTION, RISK,

    /** 앱이 모르는 값 (서버가 먼저 더한 값). 보내지 않는다 */
    @SerialName("_unknown") UNKNOWN,
}

@Serializable
enum class NetworkInstallSource { GOOGLE_PLAY, ONE_STORE, GALAXY_STORE, OTHER_STORE, BROWSER_OR_FILE, ADB, UNKNOWN }

/** contracts/classifier.md §4 근거 코드. 서버가 새 코드를 더해도 앱이 깨지지 않게 문자열로 받는다 */
@Serializable
data class NetworkReason(val code: String, val weight: Double, val evidence: JsonObject? = null)

@Serializable
data class NetworkVerdict(val level: NetworkRiskLevel = NetworkRiskLevel.UNKNOWN, val score: Double, val modelVersion: String, val reasons: List<NetworkReason>)

@Serializable
data class NetworkAppInfo(
    val packageName: String,
    val appLabel: String,
    val versionCode: Long,
    val versionName: String? = null,
    val signingCertSha256: String,
    /** 모르는 값은 계약의 UNKNOWN으로 */
    val installSource: NetworkInstallSource = NetworkInstallSource.UNKNOWN,
    val installerPackage: String? = null,
    val firstInstallTime: String,
    val lastUpdateTime: String? = null,
    val iconPngBase64: String? = null,
)

@Serializable
enum class NetworkAlertSource {
    @SerialName("install_scan") INSTALL_SCAN,
    @SerialName("symptom") SYMPTOM,

    /** 앱이 모르는 값 (서버가 먼저 더한 값). 보내지 않는다 */
    @SerialName("_unknown") UNKNOWN,
}

@Serializable
enum class NetworkAlertKind {
    @SerialName("approval") APPROVAL,
    @SerialName("notice") NOTICE,

    /** 앱이 모르는 값 (서버가 먼저 더한 값). 보내지 않는다 */
    @SerialName("_unknown") UNKNOWN,
}

@Serializable
data class NetworkSymptomEvidence(
    /** 시니어 기기 현지 날짜 YYYY-MM-DD */
    val date: String,
    val count: Int,
    val days: Int,
    val firstAt: String,
    val lastAt: String,
    val detectorVersion: String,
)

@Serializable
data class NetworkAlertCreate(
    val source: NetworkAlertSource,
    val kind: NetworkAlertKind = NetworkAlertKind.APPROVAL,
    val app: NetworkAppInfo,
    val verdict: NetworkVerdict? = null,
    val symptom: NetworkSymptomEvidence? = null,
    val detectedAt: String,
)

@Serializable
enum class NetworkAlertStatus {
    @SerialName("open") OPEN,
    @SerialName("decided") DECIDED,
    @SerialName("resolved") RESOLVED,
    @SerialName("expired") EXPIRED,

    /** 앱이 모르는 값 (서버가 먼저 더한 값). 보내지 않는다 */
    @SerialName("_unknown") UNKNOWN,
}

@Serializable
enum class NetworkDecisionType {
    @SerialName("recommend_uninstall") RECOMMEND_UNINSTALL,
    @SerialName("allow") ALLOW,

    /** 앱이 모르는 값 (서버가 먼저 더한 값). 보내지 않는다 */
    @SerialName("_unknown") UNKNOWN,
}

@Serializable
data class NetworkDecisionCreate(val decision: NetworkDecisionType, val messageToSenior: String? = null)

@Serializable
data class NetworkDecision(
    val decision: NetworkDecisionType = NetworkDecisionType.UNKNOWN,
    val messageToSenior: String? = null,
    val decidedBy: NetworkDeviceSummary,
    val decidedAt: String,
)

@Serializable
enum class NetworkOutcomeType {
    @SerialName("uninstalled") UNINSTALLED,
    @SerialName("declined") DECLINED,
    @SerialName("already_removed") ALREADY_REMOVED,
    @SerialName("suspended") SUSPENDED,
    @SerialName("allowed") ALLOWED,

    /** 앱이 모르는 값 (서버가 먼저 더한 값). 보내지 않는다 */
    @SerialName("_unknown") UNKNOWN,
}

@Serializable
data class NetworkOutcomeCreate(val outcome: NetworkOutcomeType, val occurredAt: String)

@Serializable
data class NetworkOutcome(val outcome: NetworkOutcomeType = NetworkOutcomeType.UNKNOWN, val occurredAt: String)

@Serializable
data class NetworkAlert(
    val alertId: String,
    val protectedDevice: NetworkDeviceSummary? = null,
    val linkIds: List<String>,
    val status: NetworkAlertStatus = NetworkAlertStatus.UNKNOWN,
    val source: NetworkAlertSource = NetworkAlertSource.UNKNOWN,
    val kind: NetworkAlertKind = NetworkAlertKind.UNKNOWN,
    val app: NetworkAppInfo,
    val verdict: NetworkVerdict? = null,
    val symptom: NetworkSymptomEvidence? = null,
    val decision: NetworkDecision? = null,
    val outcome: NetworkOutcome? = null,
    val detectedAt: String,
    val createdAt: String,
    val updatedAt: String? = null,
    val remindAt: String? = null,
)

@Serializable
data class NetworkAlertList(val items: List<NetworkAlert>, val nextPageToken: String? = null)
