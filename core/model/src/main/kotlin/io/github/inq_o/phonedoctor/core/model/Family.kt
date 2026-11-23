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
)
