package io.github.inq_o.phonedoctor.core.network

import io.github.inq_o.phonedoctor.core.network.model.NetworkAlert
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertCreate
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertList
import io.github.inq_o.phonedoctor.core.network.model.NetworkDecisionCreate
import io.github.inq_o.phonedoctor.core.network.model.NetworkDevice
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceRegistration
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceStatus
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceUpdate
import io.github.inq_o.phonedoctor.core.network.model.NetworkLink
import io.github.inq_o.phonedoctor.core.network.model.NetworkLinkList
import io.github.inq_o.phonedoctor.core.network.model.NetworkOutcomeCreate
import io.github.inq_o.phonedoctor.core.network.model.NetworkPairingCode
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/** openapi.yaml 경로. 인증 헤더는 [AuthInterceptor]가 붙이고, POST의 Idempotency-Key는 부르는 쪽이 정한다(재전송 때 같은 키) */
internal interface PhoneDoctorApi {
    @POST("devices")
    suspend fun registerDevice(@Header(IDEMPOTENCY_KEY) key: String, @Body body: NetworkDeviceRegistration): NetworkDevice

    @PATCH("devices/{deviceId}")
    suspend fun updateDevice(@Path("deviceId") deviceId: String, @Body body: NetworkDeviceUpdate): NetworkDevice

    @DELETE("devices/{deviceId}")
    suspend fun deleteDevice(@Path("deviceId") deviceId: String)

    @PUT("devices/{deviceId}/status")
    suspend fun reportStatus(@Path("deviceId") deviceId: String, @Body body: NetworkDeviceStatus)

    @POST("pairing-codes")
    suspend fun createPairingCode(@Header(IDEMPOTENCY_KEY) key: String): NetworkPairingCode

    @POST("pairing-codes/{code}/accept")
    suspend fun acceptPairingCode(@Header(IDEMPOTENCY_KEY) key: String, @Path("code") code: String): NetworkLink

    @GET("links")
    suspend fun listLinks(@Query("status") status: String?): NetworkLinkList

    @POST("links/{linkId}/confirm")
    suspend fun confirmLink(@Header(IDEMPOTENCY_KEY) key: String, @Path("linkId") linkId: String): NetworkLink

    @DELETE("links/{linkId}")
    suspend fun revokeLink(@Path("linkId") linkId: String)

    @POST("alerts")
    suspend fun createAlert(@Header(IDEMPOTENCY_KEY) key: String, @Body body: NetworkAlertCreate): NetworkAlert

    @GET("alerts")
    suspend fun listAlerts(
        @Query("status") status: String?,
        @Query("updatedSince") updatedSince: String?,
        @Query("pageToken") pageToken: String?,
        @Query("pageSize") pageSize: Int?,
    ): NetworkAlertList

    @GET("alerts/{alertId}")
    suspend fun getAlert(@Path("alertId") alertId: String): NetworkAlert

    @POST("alerts/{alertId}/decision")
    suspend fun decideAlert(@Header(IDEMPOTENCY_KEY) key: String, @Path("alertId") alertId: String, @Body body: NetworkDecisionCreate): NetworkAlert

    @POST("alerts/{alertId}/outcome")
    suspend fun reportOutcome(@Header(IDEMPOTENCY_KEY) key: String, @Path("alertId") alertId: String, @Body body: NetworkOutcomeCreate): NetworkAlert
}

internal const val IDEMPOTENCY_KEY = "Idempotency-Key"
