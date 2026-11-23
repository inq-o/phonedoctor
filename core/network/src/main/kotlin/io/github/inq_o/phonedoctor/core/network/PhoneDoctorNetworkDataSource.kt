package io.github.inq_o.phonedoctor.core.network

import io.github.inq_o.phonedoctor.core.network.model.NetworkAlert
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertCreate
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertList
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertStatus
import io.github.inq_o.phonedoctor.core.network.model.NetworkDecisionCreate
import io.github.inq_o.phonedoctor.core.network.model.NetworkDevice
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceRegistration
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceStatus
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceUpdate
import io.github.inq_o.phonedoctor.core.network.model.NetworkFalsePositiveReport
import io.github.inq_o.phonedoctor.core.network.model.NetworkLink
import io.github.inq_o.phonedoctor.core.network.model.NetworkLinkStatus
import io.github.inq_o.phonedoctor.core.network.model.NetworkOutcomeCreate
import io.github.inq_o.phonedoctor.core.network.model.NetworkPairingCode

/**
 * 서버 API. 실패는 [ApiException](서버가 답한 오류) 또는 IOException(망 문제)으로 던진다.
 * POST의 [key]는 Idempotency-Key(UUID). 응답을 못 받아 다시 보낼 때는 같은 키를 써야 한 번만 처리된다
 */
interface PhoneDoctorNetworkDataSource {
    suspend fun registerDevice(key: String, body: NetworkDeviceRegistration): NetworkDevice
    suspend fun updateDevice(deviceId: String, body: NetworkDeviceUpdate): NetworkDevice
    suspend fun deleteDevice(deviceId: String)
    suspend fun reportStatus(deviceId: String, body: NetworkDeviceStatus)

    suspend fun createPairingCode(key: String): NetworkPairingCode
    suspend fun acceptPairingCode(key: String, code: String): NetworkLink
    suspend fun listLinks(status: NetworkLinkStatus? = null): List<NetworkLink>
    suspend fun confirmLink(key: String, linkId: String): NetworkLink
    suspend fun revokeLink(linkId: String)

    suspend fun createAlert(key: String, body: NetworkAlertCreate): NetworkAlert
    suspend fun listAlerts(
        status: NetworkAlertStatus? = null,
        updatedSince: String? = null,
        pageToken: String? = null,
        pageSize: Int? = null,
    ): NetworkAlertList
    suspend fun getAlert(alertId: String): NetworkAlert
    suspend fun decideAlert(key: String, alertId: String, body: NetworkDecisionCreate): NetworkAlert
    suspend fun reportOutcome(key: String, alertId: String, body: NetworkOutcomeCreate): NetworkAlert

    /** 오탐 익명 신고 (US7). 로그인 토큰 없이 App Check만 붙는다. 서버는 기기를 남기지 않고 횟수만 센다 */
    suspend fun reportFalsePositive(body: NetworkFalsePositiveReport)
}
