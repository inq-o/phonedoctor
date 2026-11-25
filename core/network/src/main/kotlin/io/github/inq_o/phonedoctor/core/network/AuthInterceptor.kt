package io.github.inq_o.phonedoctor.core.network

import com.google.firebase.FirebaseNetworkException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * `Authorization: Bearer <ID 토큰>`과 `X-Firebase-AppCheck`를 붙인다.
 * 서버가 ID 토큰을 거절하면(401 id-token-invalid, 보통 만료) 새 토큰으로 한 번만 다시 보낸다.
 * OkHttp 작업 스레드에서 돌므로 토큰을 기다리는 데 runBlocking을 쓴다 (호출은 Retrofit suspend 함수가 IO에서 함)
 */
internal class AuthInterceptor(private val tokens: AuthTokens) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        // 익명 요청(오탐 신고): 로그인 토큰을 붙이지 않고, App Check도 한 번 쓰는 새 토큰으로 (다른 요청과 같은 값이 되지 않게)
        if (chain.request().header(ANONYMOUS) != null) {
            val oneTime = appCheckToken { tokens.limitedUseAppCheckToken() }
            return chain.proceed(chain.request().newBuilder().removeHeader(ANONYMOUS).header("X-Firebase-AppCheck", oneTime).build())
        }
        val appCheck = appCheckToken { tokens.appCheckToken() }
        fun send(forceRefresh: Boolean): Response {
            val id = token { tokens.idToken(forceRefresh) }
            return chain.proceed(
                chain.request().newBuilder()
                    .header("Authorization", "Bearer $id")
                    .header("X-Firebase-AppCheck", appCheck)
                    .build(),
            )
        }
        val first = send(forceRefresh = false)
        if (first.code != 401 || first.peekBody(PEEK_BYTES).string().contains("id-token-invalid").not()) return first
        first.close()
        return send(forceRefresh = true)
    }

    /**
     * 토큰 받기 실패(로그인 전, Play Integrity 오류 등)를 IOException으로 바꾼다.
     * OkHttp는 인터셉터가 던진 IOException 밖의 예외를 작업 스레드에서 다시 던져 앱을 죽인다
     */
    private fun token(block: suspend () -> String): String = try {
        runBlocking { block() }
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException("토큰을 받지 못함", e)
    }

    /** App Check 토큰 받기 실패 중 망 문제가 아닌 것은 [AppCheckException]으로 (화면이 "인터넷 없음"으로 잘못 안내하지 않게) */
    private fun appCheckToken(block: suspend () -> String): String = try {
        runBlocking { block() }
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        val network = generateSequence<Throwable>(e) { it.cause }.any { it is FirebaseNetworkException || it is IOException }
        throw if (network) IOException("망 문제로 App Check 토큰을 받지 못함", e) else AppCheckException(e)
    }

    private companion object {
        const val PEEK_BYTES = 4096L
    }
}
