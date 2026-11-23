package io.github.inq_o.phonedoctor.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 서버 시각과 이 기기 시각의 차이. 서버 응답의 `Date` 머리로 잰다 (초 단위).
 * 서버가 기록한 시각(예: 보호자 응답 시각)을 기기 시계로 옮길 때 쓴다: 기기 시계가 틀려도 "답한 뒤 24시간"이 늘거나 줄지 않게
 */
@Singleton
class ServerClock @Inject constructor() {
    private val offset = AtomicReference<Long?>(null)

    /** 서버 시각 - 기기 시각 (ms). 아직 응답을 받은 적 없으면 null */
    fun offsetMillis(): Long? = offset.get()

    fun record(serverMillis: Long, localMillis: Long) = offset.set(serverMillis - localMillis)
}

internal class ServerDateInterceptor(private val clock: ServerClock) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        response.headers.getDate("Date")?.let { clock.record(it.time, System.currentTimeMillis()) }
        return response
    }
}
