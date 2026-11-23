package io.github.inq_o.phonedoctor.server

import com.google.auth.oauth2.TokenVerifier as GoogleTokenVerifier
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** 예약 작업 호출 인증. 운영은 Cloud Scheduler의 OIDC 토큰, 시험은 가짜 */
fun interface SchedulerAuth {
    fun verify(bearer: String): Boolean
}

/**
 * Cloud Scheduler가 붙이는 Google 서명 ID 토큰 확인: 대상(audience)이 이 경로 URL이고 보낸 서비스 계정이 맞는지.
 * 절차: cloud.google.com/run/docs/authenticating/service-to-service
 */
class GoogleOidcAuth(private val audience: String, private val serviceAccount: String) : SchedulerAuth {
    private val verifier = GoogleTokenVerifier.newBuilder().setAudience(audience).setIssuer("https://accounts.google.com").build()

    override fun verify(bearer: String): Boolean = try {
        val payload = verifier.verify(bearer).payload
        payload["email"] == serviceAccount && payload["email_verified"] == true
    } catch (_: GoogleTokenVerifier.VerificationException) {
        false
    } catch (_: IllegalArgumentException) {
        // 점이 없거나 base64가 아닌 등 형식이 깨진 토큰 (JsonWebSignature.parse). 500이 아니라 401로
        false
    }
}

@Serializable
data class TickResult(
    val reminded: Int,
    val expired: Int,
    val deletedAlerts: Int,
    val contactLost: Int,
    val deletedDevices: Int,
    val deletedCodes: Int,
    val deletedKeys: Int,
    val deletedFeedback: Int = 0,
)

/** F6 연락 끊김 기준 (ux-flows F6) */
val CONTACT_LOST_AFTER: Duration = Duration.ofHours(48)

/** 마지막 접속 뒤 이만큼 지나면 기기 삭제 (data-model §2) */
val DEVICE_DELETE_AFTER: Duration = Duration.ofDays(180)

/**
 * 시간이 지나 생기는 일을 한 번에 처리한다. Cloud Scheduler가 15분마다 부른다 (README 배포 절).
 * 단계마다 트랜잭션을 커밋한 뒤 바로 그 단계의 푸시를 보낸다: 뒤 단계가 실패해도 이미 표시를 바꾼(커밋한) 앞 단계의 푸시는 나간다.
 * 겹쳐 불려도(재시도) 같은 푸시가 두 번 가지 않게 대상 행을 잠그고 같은 트랜잭션에서 표시를 바꾼다.
 * 푸시는 최대 1번이다(보낸 직후 서버가 죽으면 다시 보내지 않음). 앱이 목록을 당겨 와 보완한다 (push-messages.md §1)
 */
fun runTick(deps: Deps): TickResult {
    val now = deps.now()

    /** [block]을 커밋하고 그 단계의 푸시를 보낸다 */
    fun <T> step(block: (MutableList<Push>) -> T): T {
        val pushes = mutableListOf<Push>()
        val result = transaction(deps.db) { block(pushes) }
        deps.sendAll(pushes)
        return result
    }

    // 1. 7일 무응답 → expired (FR-023). 앱은 목록을 당겨 올 때 반영 (푸시 없음)
    val expired = step {
        Alerts.update({ (Alerts.status eq AlertStatus.OPEN.db) and (Alerts.expiresAt lessEq now.utc()) }) {
            it[status] = AlertStatus.EXPIRED.db
            it[remindAt] = null
            it[updatedAt] = now.utc()
        }
    }

    // 2. 보호자 재알림 (FR-026, 24시간 무응답 1회). 만료 처리 뒤에 해, 예약 작업이 늦게 불려도 만료된 요청에는 보내지 않는다.
    //    응답과 동시에 와도 요청 행 잠금으로 하나씩
    val reminded = step { pushes ->
        val due = Alerts.selectAll().where {
            (Alerts.status eq AlertStatus.OPEN.db) and Alerts.remindAt.isNotNull() and (Alerts.remindAt lessEq now.utc()) and
                (Alerts.expiresAt greater now.utc())
        }.forUpdate(ForUpdateOption.ForUpdate).map { it[Alerts.alertId] }
        for (id in due) {
            Alerts.update({ Alerts.alertId eq id }) { it[remindAt] = null }
            val view = alertView(id)
            activeRecipients(id).forEach { (_, g) -> pushes += alertPush(view, g, reminder = true) }
        }
        due.size
    }

    // 3. 보관 기간(생성 + 90일)이 지난 요청 삭제 (NFR-09)
    val deletedAlerts = step { Alerts.deleteWhere { Alerts.deleteAt lessEq now.utc() } }

    // 4. 연락 끊김 (F6): 확정된 연결에서 기준 시각 = max(마지막 검사 보고, 연결 확정)이 48시간 넘게 지났으면 보호자에게 1번.
    //    알린 기준 시각을 남겨, 기준이 바뀐 뒤(새 보고가 옴) 다시 끊기면 또 알린다
    val contactLost = step { pushes ->
        fun baseline(lastChecked: java.time.OffsetDateTime?, activated: java.time.OffsetDateTime?): Instant =
            listOfNotNull(lastChecked?.toInstant(), activated?.toInstant()).max()
        fun due(r: ResultRow): Boolean {
            val since = baseline(r[Devices.lastCheckedAt], r[Links.activatedAt])
            val notified = r[Links.contactLostBaseline]?.toInstant()
            return since.plus(CONTACT_LOST_AFTER) <= now && (notified == null || since > notified)
        }
        val join = Links.join(Devices, JoinType.INNER, Links.protectedDeviceId, Devices.deviceId)
        val columns = listOf<Expression<*>>(Links.linkId, Links.guardianDeviceId, Links.activatedAt, Links.contactLostBaseline, Devices.lastCheckedAt, Devices.displayName)
        // 후보는 잠그지 않고 고른 뒤, 그 연결 행만 ID 순으로 잠그고 다시 확인한다.
        // 시니어 기기까지 잠그면 연결 해제(시니어 기기 → 연결 순)와 순서가 엇갈려 교착할 수 있고, 모든 연결을 잠그면 15분마다 확정·해제가 막힌다
        val candidates = join.select(columns).where { Links.status eq LinkStatus.ACTIVE.db }.filter(::due).map { it[Links.linkId] }.sorted()
        var n = 0
        for (linkId in candidates) {
            val r = join.select(columns).where { (Links.linkId eq linkId) and (Links.status eq LinkStatus.ACTIVE.db) }
                .forUpdate(ForUpdateOption.PostgreSQL.ForUpdate(null, Links)).singleOrNull() ?: continue
            if (!due(r)) continue
            Links.update({ Links.linkId eq linkId }) { it[contactLostBaseline] = baseline(r[Devices.lastCheckedAt], r[Links.activatedAt]).utc() }
            pushes += PushType.CONTACT_LOST.to(fcmToken(r[Links.guardianDeviceId]), "linkId" to linkId, "protectedName" to r[Devices.displayName])
            n++
        }
        n
    }

    // 5. 오래 접속하지 않은 기기 삭제 (data-model §2, 마지막 접속 180일). 연결 상대에게 LINK_REVOKED
    val deletedDevices = step { pushes ->
        val cutoff = now.minus(DEVICE_DELETE_AFTER).utc()
        val stale = Devices.select(Devices.deviceId).where {
            (Devices.lastSeenAt less cutoff) or (Devices.lastSeenAt.isNull() and (Devices.createdAt less cutoff))
        }.orderBy(Devices.deviceId).forUpdate(ForUpdateOption.ForUpdate).map { it[Devices.deviceId] }
        stale.forEach { pushes += deleteDevice(it) }
        stale.size
    }

    // 6. 만료 1시간 지난 연결 코드, 1분 지난 코드 입력 시도
    val deletedCodes = step {
        PairingAttempts.deleteWhere { PairingAttempts.attemptedAt less now.minus(Duration.ofMinutes(1)).utc() }
        PairingCodes.deleteWhere { PairingCodes.expiresAt less now.minus(CODE_KEEP_AFTER_EXPIRY).utc() }
    }

    // 7. 보관 기간(24시간)이 지난 재전송 확인용 저장 응답. POST가 올 때의 정리(Idempotency.kt)만으로는 요청이 없으면 계속 남는다
    val deletedKeys = step { IdempotencyKeys.deleteWhere { IdempotencyKeys.createdAt less now.minus(IDEMPOTENCY_KEEP).utc() } }

    // 오탐 신고 집계: 마지막 신고 뒤 1년
    val deletedFeedback = step {
        val cutoff = now.atOffset(ZoneOffset.UTC).toLocalDate().minusDays(FALSE_POSITIVE_KEEP_DAYS)
        FalsePositives.deleteWhere { FalsePositives.lastDay less cutoff }
    }

    return TickResult(reminded, expired, deletedAlerts, contactLost, deletedDevices, deletedCodes, deletedKeys, deletedFeedback)
}

/** 예약 작업 경로. 앱 토큰(`/v1`)이 아니라 Cloud Scheduler 토큰으로 인증. 설정이 없으면 꺼 둔다(404) */
fun Route.jobRoutes(deps: Deps) {
    post("/internal/tick") {
        val auth = deps.scheduler ?: throw notFound("예약 작업이 설정되지 않음")
        val token = call.request.headers["Authorization"]?.removePrefix("Bearer ")?.trim()
        if (token.isNullOrEmpty() || !withContext(Dispatchers.IO) { auth.verify(token) }) {
            throw ApiProblem(HttpStatusCode.Unauthorized, "scheduler-token-invalid", "인증 실패", "Cloud Scheduler OIDC 토큰이 필요함")
        }
        call.respond(withContext(Dispatchers.IO) { runTick(deps) })
    }
}
