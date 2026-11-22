package io.github.inq_o.phonedoctor.server

import com.auth0.jwk.JwkException
import com.auth0.jwk.JwkProvider
import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.exceptions.JWTVerificationException
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.util.AttributeKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.net.URI
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.TimeUnit

/** 인증된 요청자. Firebase 사용자 1명 = 기기 1대 (data-model §2) */
data class Caller(
    val uid: String,
    /** Google 계정 이름 (보호자 기본 표시 이름) */
    val name: String?,
    /** `anonymous`(시니어) 또는 `google.com`(보호자) */
    val signInProvider: String?,
)

/** Firebase ID 토큰과 App Check 토큰 검증. 시험에서는 가짜로 바꾼다 */
interface TokenVerifier {
    /** 유효하지 않으면 null */
    fun verifyIdToken(token: String): Caller?

    fun verifyAppCheck(token: String): Boolean
}

private val CallerKey = AttributeKey<Caller>("caller")

val ApplicationCall.caller: Caller get() = attributes[CallerKey]

class AuthConfig {
    lateinit var deps: Deps
}

/**
 * 모든 요청에 `Authorization: Bearer <ID 토큰>` + `X-Firebase-AppCheck` (openapi 공통 규칙).
 * 통과하면 그 기기의 `lastSeenAt`을 갱신한다(1분에 한 번까지). `lastCheckedAt`은 상태 보고로만 바뀜
 */
val FirebaseAuthPlugin = createRouteScopedPlugin("FirebaseAuth", ::AuthConfig) {
    val deps = pluginConfig.deps
    onCall { call ->
        val idToken = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        val appCheck = call.request.headers["X-Firebase-AppCheck"]
        if (idToken.isNullOrEmpty()) throw unauthorized("id-token-missing", "Firebase ID 토큰이 없음")
        if (appCheck.isNullOrEmpty()) throw unauthorized("app-check-missing", "App Check 토큰이 없음")
        val caller = withContext(Dispatchers.IO) {
            val c = deps.verifier.verifyIdToken(idToken) ?: throw unauthorized("id-token-invalid", "Firebase ID 토큰이 유효하지 않거나 만료됨")
            if (!deps.verifier.verifyAppCheck(appCheck)) throw unauthorized("app-check-invalid", "App Check 토큰이 유효하지 않음")
            touchLastSeen(deps, c.uid)
            c
        }
        call.attributes.put(CallerKey, caller)
    }
}

private fun unauthorized(slug: String, detail: String) = ApiProblem(HttpStatusCode.Unauthorized, slug, "인증 실패", detail)

private fun touchLastSeen(deps: Deps, uid: String) {
    val now = deps.now()
    transaction(deps.db) {
        Devices.update({ (Devices.firebaseUid eq uid) and (Devices.lastSeenAt.isNull() or (Devices.lastSeenAt less now.minusSeconds(60).utc())) }) {
            it[lastSeenAt] = now.utc()
        }
    }
}

/** ID 토큰 검증과 FCM 전송이 함께 쓰는 Firebase 앱. 인증 정보는 기본 인증 정보(ADC) */
fun firebaseApp(config: Config): FirebaseApp = FirebaseApp.initializeApp(
    FirebaseOptions.builder()
        .setCredentials(GoogleCredentials.getApplicationDefault())
        .setProjectId(config.firebaseProjectId)
        // 기본값은 제한 없음(0). FCM이 멈추면 커밋은 끝났는데 응답이 안 나가므로 끊고 실패로 처리 (Push.kt sendAll)
        .setConnectTimeout(3_000)
        .setReadTimeout(5_000)
        .build(),
)

/**
 * 실제 검증. ID 토큰은 Firebase Admin SDK, App Check 토큰은 공개 키(JWKS)로 직접 검증한다
 * (Java Admin SDK에는 App Check 검증이 없음. 절차: firebase.google.com/docs/app-check/custom-resource-backend)
 */
class FirebaseVerifier(private val config: Config, app: FirebaseApp) : TokenVerifier {
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(app)
    private val jwks: JwkProvider = JwkProviderBuilder(URI("https://firebaseappcheck.googleapis.com/v1/jwks").toURL())
        // 키 서버가 멈춰도 요청이 무기한 기다리지 않게 (기본은 읽기 제한 없음)
        .timeouts(3_000, 3_000)
        .cached(10, 6, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    override fun verifyIdToken(token: String): Caller? = try {
        val t = auth.verifyIdToken(token)
        val provider = (t.claims["firebase"] as? Map<*, *>)?.get("sign_in_provider") as? String
        Caller(t.uid, t.name, provider)
    } catch (_: FirebaseAuthException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    override fun verifyAppCheck(token: String): Boolean = try {
        val jwt = JWT.decode(token)
        val kid = jwt.keyId ?: return false
        val key = jwks.get(kid).publicKey as RSAPublicKey
        val verified = JWT.require(Algorithm.RSA256(key, null))
            .withIssuer("https://firebaseappcheck.googleapis.com/${config.firebaseProjectNumber}")
            .withAudience("projects/${config.firebaseProjectNumber}")
            .build()
            .verify(jwt)
        verified.subject in config.appCheckAppIds
    } catch (_: JWTVerificationException) {
        false
    } catch (_: JwkException) {
        false
    }
}
