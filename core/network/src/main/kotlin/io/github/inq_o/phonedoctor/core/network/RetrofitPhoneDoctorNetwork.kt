package io.github.inq_o.phonedoctor.core.network

import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertCreate
import io.github.inq_o.phonedoctor.core.network.model.NetworkAlertStatus
import io.github.inq_o.phonedoctor.core.network.model.NetworkDecisionCreate
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceRegistration
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceStatus
import io.github.inq_o.phonedoctor.core.network.model.NetworkDeviceUpdate
import io.github.inq_o.phonedoctor.core.network.model.NetworkLinkStatus
import io.github.inq_o.phonedoctor.core.network.model.NetworkOutcomeCreate
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import retrofit2.HttpException
import java.io.IOException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/** Retrofit 구현. HTTP 오류를 [ApiException]으로 바꿔 던진다 */
internal class RetrofitPhoneDoctorNetwork(baseUrl: String, callFactory: Call.Factory, private val json: Json) : PhoneDoctorNetworkDataSource {
    private val api = Retrofit.Builder()
        .baseUrl(baseUrl)
        .callFactory(callFactory)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(PhoneDoctorApi::class.java)

    private suspend fun <T> call(block: suspend PhoneDoctorApi.() -> T): T = try {
        api.block()
    } catch (e: HttpException) {
        throw ApiException.from(e, json)
    } catch (e: SerializationException) {
        // 성공 응답인데 본문을 읽지 못함(필수 필드 없음, 프록시 HTML 등). 약속한 두 종류(ApiException·IOException) 밖으로 새지 않게
        throw UnreadableResponseException(e)
    }

    /** 질의 문자열에 쓰는 열거값 이름 (본문과 같은 @SerialName 값) */
    private inline fun <reified T> wire(value: T): String = json.encodeToJsonElement(serializer<T>(), value).jsonPrimitive.content

    override suspend fun registerDevice(key: String, body: NetworkDeviceRegistration) = call { registerDevice(key, body) }
    override suspend fun updateDevice(deviceId: String, body: NetworkDeviceUpdate) = call { updateDevice(deviceId, body) }
    override suspend fun deleteDevice(deviceId: String) = call { deleteDevice(deviceId) }
    override suspend fun reportStatus(deviceId: String, body: NetworkDeviceStatus) = call { reportStatus(deviceId, body) }

    override suspend fun createPairingCode(key: String) = call { createPairingCode(key) }
    override suspend fun acceptPairingCode(key: String, code: String) = call { acceptPairingCode(key, code) }
    override suspend fun listLinks(status: NetworkLinkStatus?) = call { listLinks(status?.let { wire(it) }).items }
    override suspend fun confirmLink(key: String, linkId: String) = call { confirmLink(key, linkId) }
    override suspend fun revokeLink(linkId: String) = call { revokeLink(linkId) }

    override suspend fun createAlert(key: String, body: NetworkAlertCreate) = call { createAlert(key, body) }
    override suspend fun listAlerts(status: NetworkAlertStatus?, updatedSince: String?, pageToken: String?, pageSize: Int?) =
        call { listAlerts(status?.let { wire(it) }, updatedSince, pageToken, pageSize) }
    override suspend fun getAlert(alertId: String) = call { getAlert(alertId) }
    override suspend fun decideAlert(key: String, alertId: String, body: NetworkDecisionCreate) = call { decideAlert(key, alertId, body) }
    override suspend fun reportOutcome(key: String, alertId: String, body: NetworkOutcomeCreate) = call { reportOutcome(key, alertId, body) }
}

/** 성공 응답의 본문을 읽지 못함 (IOException이라 부르는 쪽은 망 문제처럼 다시 시도한다) */
class UnreadableResponseException(cause: Throwable) : IOException("응답 본문을 읽지 못함", cause)
