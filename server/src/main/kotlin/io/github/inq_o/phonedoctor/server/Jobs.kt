package io.github.inq_o.phonedoctor.server

import com.google.auth.oauth2.TokenVerifier as GoogleTokenVerifier
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
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
)

/** F6 연락 끊김 기준 (ux-flows F6) */
val CONTACT_LOST_AFTER: Duration = Duration.ofHours(48)

/** 마지막 접속 뒤 이만큼 지나면 기기 삭제 (data-model §2) */
val DEVICE_DELETE_AFTER: Duration = Duration.ofDays(180)

/**
 * 시간이 지나 생기는 일을 한 번에 처리한다. Cloud Scheduler가 15분마다 부른다 (README 배포 절).
 * 겹쳐 불려도(재시도) 같은 푸시가 두 번 가지 않게 대상 행을 잠그고 같은 트랜잭션에서 표시를 바꾼다.
 * 푸시는 커밋 뒤에 보낸다
 */
fun runTick(deps: Deps): TickResult {
    val now = deps.now()
    val pushes = mutableListOf<Push>()

    // 1. 보호자 재알림 (FR-026, 24시간 무응답 1회). 응답과 동시에 와도 요청 행 잠금으로 하나씩
    val reminded = transaction(deps.db) {
        val due = Alerts.selectAll().where {
            (Alerts.status eq AlertStatus.OPEN.db) and Alerts.remindAt.isNotNull() and (Alerts.remindAt lessEq now.utc())
        }.forUpdate(ForUpdateOption.ForUpdate).map { it[Alerts.alertId] }
        for (id in due) {
            Alerts.update({ Alerts.alertId eq id }) { it[remindAt] = null }
            val view = alertView(id)
            activeRecipients(id).forEach { (_, g) -> pushes += alertPush(view, g, reminder = true) }
        }
        due.size
    }

    // 2. 7일 무응답 → expired (FR-023). 앱은 목록을 당겨 올 때 반영 (푸시 없음)
    val expired = transaction(deps.db) {
        Alerts.update({ (Alerts.status eq AlertStatus.OPEN.db) and (Alerts.expiresAt lessEq now.utc()) }) {
            it[status] = AlertStatus.EXPIRED.db
            it[remindAt] = null
            it[updatedAt] = now.utc()
        }
    }

    // 3. 보관 기간(생성 + 90일)이 지난 요청 삭제 (NFR-09)
    val deletedAlerts = transaction(deps.db) { Alerts.deleteWhere { Alerts.deleteAt lessEq now.utc() } }

    // 4. 연락 끊김 (F6): 확정된 연결에서 마지막 검사 보고(없으면 연결 확정 시각)가 48시간 넘게 없으면 보호자에게 1번.
    //    알린 뒤 새 보고가 오면 다음 끊김 때 다시 알린다
    val contactLost = transaction(deps.db) {
        val rows = Links.join(Devices, JoinType.INNER, Links.protectedDeviceId, Devices.deviceId)
            .select(Links.linkId, Links.guardianDeviceId, Links.activatedAt, Links.contactLostAt, Devices.lastCheckedAt, Devices.displayName)
            .where { Links.status eq LinkStatus.ACTIVE.db }
            .forUpdate(ForUpdateOption.ForUpdate)
            .toList()
        var n = 0
        for (r in rows) {
            val since = r[Devices.lastCheckedAt]?.toInstant() ?: r[Links.activatedAt]!!.toInstant()
            val notified = r[Links.contactLostAt]?.toInstant()
            if (since.plus(CONTACT_LOST_AFTER) > now || (notified != null && notified >= since)) continue
            val linkId = r[Links.linkId]
            Links.update({ Links.linkId eq linkId }) { it[contactLostAt] = now.utc() }
            pushes += PushType.CONTACT_LOST.to(fcmToken(r[Links.guardianDeviceId]), "linkId" to linkId, "protectedName" to r[Devices.displayName])
            n++
        }
        n
    }

    // 5. 오래 접속하지 않은 기기 삭제 (data-model §2, 마지막 접속 180일). 연결 상대에게 LINK_REVOKED
    val deletedDevices = transaction(deps.db) {
        val cutoff = now.minus(DEVICE_DELETE_AFTER).utc()
        val stale = Devices.select(Devices.deviceId).where {
            (Devices.lastSeenAt less cutoff) or (Devices.lastSeenAt.isNull() and (Devices.createdAt less cutoff))
        }.forUpdate(ForUpdateOption.ForUpdate).map { it[Devices.deviceId] }
        stale.forEach { pushes += deleteDevice(it) }
        stale.size
    }

    // 6. 만료 1시간 지난 연결 코드, 1분 지난 코드 입력 시도
    val deletedCodes = transaction(deps.db) {
        PairingAttempts.deleteWhere { PairingAttempts.attemptedAt less now.minus(Duration.ofMinutes(1)).utc() }
        PairingCodes.deleteWhere { PairingCodes.expiresAt less now.minus(CODE_KEEP_AFTER_EXPIRY).utc() }
    }

    deps.sendAll(pushes)
    return TickResult(reminded, expired, deletedAlerts, contactLost, deletedDevices, deletedCodes)
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
