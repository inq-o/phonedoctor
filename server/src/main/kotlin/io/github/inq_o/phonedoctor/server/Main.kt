package io.github.inq_o.phonedoctor.server

import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.jetbrains.exposed.v1.jdbc.Database
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicReference

/** 환경 변수 설정 (Cloud Run). 값과 받는 곳은 server/README.md */
data class Config(
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    val firebaseProjectId: String,
    val firebaseProjectNumber: String,
    /** App Check 토큰의 `sub`로 받을 Firebase 앱 ID */
    val appCheckAppIds: Set<String>,
    /** 예약 작업 경로(`/internal/tick`)를 부르는 Cloud Scheduler의 OIDC 대상·서비스 계정. 둘 다 있어야 켜짐 */
    val schedulerAudience: String?,
    val schedulerServiceAccount: String?,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun req(name: String) = env[name]?.takeIf { it.isNotBlank() } ?: error("환경 변수 $name 이 없음")
            return Config(
                port = env["PORT"]?.toInt() ?: 8080,
                databaseUrl = req("DATABASE_URL"),
                databaseUser = req("DATABASE_USER"),
                databasePassword = req("DATABASE_PASSWORD"),
                firebaseProjectId = req("FIREBASE_PROJECT_ID"),
                firebaseProjectNumber = req("FIREBASE_PROJECT_NUMBER"),
                appCheckAppIds = req("APP_CHECK_APP_IDS").split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
                schedulerAudience = env["SCHEDULER_AUDIENCE"]?.takeIf { it.isNotBlank() },
                schedulerServiceAccount = env["SCHEDULER_SERVICE_ACCOUNT"]?.takeIf { it.isNotBlank() },
            ).also {
                // 한쪽만 있으면 예약 작업이 조용히 꺼진 채 돌아 재알림·만료·삭제가 멈춘다. 시작에서 바로 실패
                check((it.schedulerAudience == null) == (it.schedulerServiceAccount == null)) {
                    "SCHEDULER_AUDIENCE와 SCHEDULER_SERVICE_ACCOUNT는 함께 설정하거나 둘 다 비워야 함"
                }
            }
        }
    }
}

class Deps(
    val db: Database,
    val verifier: TokenVerifier,
    val pusher: Pusher,
    val clock: Clock = Clock.systemUTC(),
    /** 없으면 예약 작업 경로를 꺼 둔다 */
    val scheduler: SchedulerAuth? = null,
) {
    /** 만료된 Idempotency-Key를 마지막으로 지운 시각 (Idempotency.kt) */
    val keysSweptAt = AtomicReference(Instant.EPOCH)

    /** DB 정밀도(µs)와 응답이 어긋나지 않게 ms로 자른 현재 시각 */
    fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.MILLIS)
}

fun main() {
    val config = Config.fromEnv()
    val firebase = firebaseApp(config)
    val scheduler = if (config.schedulerAudience != null && config.schedulerServiceAccount != null) {
        GoogleOidcAuth(config.schedulerAudience, config.schedulerServiceAccount)
    } else {
        null
    }
    val deps = Deps(connect(dataSource(config)), FirebaseVerifier(config, firebase), FcmPusher(firebase), scheduler = scheduler)
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { module(deps) }.start(wait = true)
}

fun Application.module(deps: Deps) {
    install(ContentNegotiation) { json(ApiJson) }
    install(CallLogging)
    installProblems()
    routing {
        get("/healthz") { call.respondText("ok") }
        jobRoutes(deps)
        route("/v1") {
            install(FirebaseAuthPlugin) { this.deps = deps }
            deviceRoutes(deps)
            pairingRoutes(deps)
            alertRoutes(deps)
            feedbackRoutes(deps)
        }
    }
}
