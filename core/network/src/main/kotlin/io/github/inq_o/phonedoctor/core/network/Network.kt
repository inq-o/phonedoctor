package io.github.inq_o.phonedoctor.core.network

import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 서버 본문 형식 (서버 ApiJson과 같은 설정). 모르는 필드는 무시, null 필드는 보내지 않고 기본값은 보냄(예: kind = approval).
 * 모르는 열거값은 그 필드의 기본값(UNKNOWN)으로 읽는다: 서버가 먼저 값을 더해도 목록 전체를 못 읽는 일이 없게
 */
internal val networkJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    coerceInputValues = true
}

/** 시니어 폰은 망이 약할 수 있어 기다리되 무한정은 아니게. 실패하면 부르는 쪽(WorkManager)이 다시 시도한다 */
internal fun networkClient(tokens: AuthTokens): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(20, TimeUnit.SECONDS)
    .addInterceptor(AuthInterceptor(tokens))
    .build()

internal fun networkDataSource(baseUrl: String, client: OkHttpClient, json: Json): PhoneDoctorNetworkDataSource =
    RetrofitPhoneDoctorNetwork(baseUrl, client, json)
