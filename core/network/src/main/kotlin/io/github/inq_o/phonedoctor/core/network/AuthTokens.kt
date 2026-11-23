package io.github.inq_o.phonedoctor.core.network

import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import java.io.IOException
import javax.inject.Inject

/** 요청마다 붙이는 토큰 (openapi 공통 규칙). 시험에서는 가짜로 바꾼다 */
interface AuthTokens {
    /** Firebase ID 토큰. [forceRefresh]면 캐시를 버리고 새로 받는다 (서버가 만료로 거절했을 때) */
    suspend fun idToken(forceRefresh: Boolean): String

    /** App Check 토큰 (Play Integrity) */
    suspend fun appCheckToken(): String

    /**
     * 한 번 쓰고 버리는 App Check 토큰. 익명 요청에 쓴다: 캐시 토큰은 1시간 동안 다른 요청과 같은 값이라
     * 그 값으로 익명 신고와 로그인한 요청을 이을 수 있다
     */
    suspend fun limitedUseAppCheckToken(): String
}

/**
 * 로그인 전에 서버를 부름. 시니어는 익명 로그인, 보호자는 Google 로그인을 먼저 해야 한다.
 * IOException인 이유: OkHttp 인터셉터에서 다른 예외를 던지면 작업 스레드에서 다시 던져져 앱이 죽는다
 */
class NotSignedInException : IOException("Firebase 로그인 전")

internal class FirebaseAuthTokens @Inject constructor() : AuthTokens {
    override suspend fun idToken(forceRefresh: Boolean): String {
        val user = FirebaseAuth.getInstance().currentUser ?: throw NotSignedInException()
        return user.getIdToken(forceRefresh).await().token ?: throw NotSignedInException()
    }

    override suspend fun appCheckToken(): String = FirebaseAppCheck.getInstance().getAppCheckToken(false).await().token

    override suspend fun limitedUseAppCheckToken(): String = FirebaseAppCheck.getInstance().limitedUseAppCheckToken.await().token
}
