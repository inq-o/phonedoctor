package io.github.inq_o.phonedoctor.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import java.time.Duration
import java.util.UUID

// openapi.yaml 의 pairing 부분 (POST /pairing-codes, /pairing-codes/{code}/accept, /links)

@Serializable
data class PairingCode(val code: String, val qrPayload: String, val expiresAt: Ts)

@Serializable
enum class LinkStatus(val db: String) {
    @SerialName("pending") PENDING("pending"),
    @SerialName("active") ACTIVE("active"),
    @SerialName("revoked") REVOKED("revoked"),
    ;

    companion object {
        fun of(db: String) = entries.firstOrNull { it.db == db }
    }
}

@Serializable
data class DeviceSummary(val deviceId: String, val displayName: String)

@Serializable
data class Link(
    val linkId: String,
    val status: LinkStatus,
    val protectedDevice: DeviceSummary,
    val guardianDevice: DeviceSummary,
    val createdAt: Ts,
    val activatedAt: Ts? = null,
    val lastSeenAt: Ts? = null,
    val lastCheckedAt: Ts? = null,
    val protection: ProtectionState? = null,
)

@Serializable
data class LinkList(val items: List<Link>)

private val CODE_VALID = Duration.ofMinutes(10)

/** 만료 뒤에도 이만큼은 남겨 두어 "만료됨"(410)으로 알려 준다 (data-model §2: 만료 1시간 후 삭제) */
private val CODE_KEEP_AFTER_EXPIRY = Duration.ofHours(1)

/** 시니어 1명에게 연결할 수 있는 보호자 수 상한 (data-model §2). 무료 1명 제한은 구독(T084)을 붙일 때 */
const val MAX_GUARDIANS = 3

private val ATTEMPT_WINDOW = Duration.ofMinutes(1)
private const val ATTEMPT_MAX = 5

private val random = SecureRandom()

/** 요청자의 기기. 등록 전이면 403 */
fun myDevice(caller: Caller, lock: Boolean = false): ResultRow {
    val query = Devices.selectAll().where { Devices.firebaseUid eq caller.uid }
    return (if (lock) query.forUpdate(ForUpdateOption.ForUpdate) else query).singleOrNull()
        ?: throw ApiProblem(HttpStatusCode.Forbidden, "device-not-registered", "기기 등록 필요", "먼저 POST /devices")
}

private fun ResultRow.isRole(role: DeviceRole) = this[Devices.role] == role.db

/** 링크 행들을 응답 형태로. 시니어 기기의 접속·검사·권한 상태는 연결이 확정된(active) 링크에만 싣는다 */
private fun linkViews(rows: List<ResultRow>): List<Link> {
    val ids = rows.flatMap { listOf(it[Links.protectedDeviceId], it[Links.guardianDeviceId]) }.distinct()
    val devices = Devices.selectAll().where { Devices.deviceId inList ids }.associateBy { it[Devices.deviceId] }
    return rows.map { row ->
        val p = devices.getValue(row[Links.protectedDeviceId])
        val g = devices.getValue(row[Links.guardianDeviceId])
        val status = LinkStatus.of(row[Links.status])!!
        val active = status == LinkStatus.ACTIVE
        Link(
            linkId = row[Links.linkId],
            status = status,
            protectedDevice = DeviceSummary(p[Devices.deviceId], p[Devices.displayName]),
            guardianDevice = DeviceSummary(g[Devices.deviceId], g[Devices.displayName]),
            createdAt = row[Links.createdAt].toInstant(),
            activatedAt = row[Links.activatedAt]?.toInstant(),
            lastSeenAt = p[Devices.lastSeenAt]?.toInstant()?.takeIf { active },
            lastCheckedAt = p[Devices.lastCheckedAt]?.toInstant()?.takeIf { active },
            protection = p[Devices.protection]?.takeIf { active },
        )
    }
}

private fun linkView(linkId: String) = linkViews(Links.selectAll().where { Links.linkId eq linkId }.toList()).single()

private fun deviceRow(deviceId: String) = Devices.selectAll().where { Devices.deviceId eq deviceId }.single()

private fun activeLinkCount(protectedId: String) =
    Links.selectAll().where { (Links.protectedDeviceId eq protectedId) and (Links.status eq LinkStatus.ACTIVE.db) }.count()

private fun guardianLimit() =
    ApiProblem(HttpStatusCode.Conflict, "guardian-limit", "연결할 수 있는 보호자 수를 넘음", "보호자는 최대 ${MAX_GUARDIANS}명")

/**
 * 연결 코드 입력 시도를 센다 (NFR-08, 기기당 분당 5회). 넘으면 429 + Retry-After.
 * 코드 처리와 다른 트랜잭션으로 먼저 커밋해, 틀린 코드(404·410)로 처리가 되돌려져도 시도는 남는다.
 * 같은 기기의 시도가 동시에 와도 한 번씩 세도록 기기 행을 잠근다
 */
private fun countAttempt(deps: Deps, caller: Caller) = transaction(deps.db) {
    val me = myDevice(caller, lock = true)
    if (!me.isRole(DeviceRole.GUARDIAN)) throw forbidden("보호자 기기만 코드를 입력함")
    val now = deps.now()
    val since = now.minus(ATTEMPT_WINDOW)
    // 정확히 1분 지난 시도도 뺀다 (Retry-After 시각에 다시 보내면 통과)
    PairingAttempts.deleteWhere { (PairingAttempts.firebaseUid eq caller.uid) and (PairingAttempts.attemptedAt lessEq since.utc()) }
    val recent = PairingAttempts.selectAll().where { PairingAttempts.firebaseUid eq caller.uid }
        .orderBy(PairingAttempts.attemptedAt, SortOrder.ASC)
        .map { it[PairingAttempts.attemptedAt].toInstant() }
    if (recent.size >= ATTEMPT_MAX) {
        val wait = Duration.between(now, recent.first().plus(ATTEMPT_WINDOW))
        val seconds = maxOf(1, (wait.toMillis() + 999) / 1000)
        throw ApiProblem(
            HttpStatusCode.TooManyRequests, "too-many-attempts", "코드 입력 횟수 초과", "${seconds}초 뒤 다시 시도",
            headers = mapOf("Retry-After" to seconds.toString()),
        )
    }
    PairingAttempts.insert {
        it[firebaseUid] = caller.uid
        it[attemptedAt] = now.utc()
    }
}

fun Route.pairingRoutes(deps: Deps) {
    // (시니어) 연결 코드 발급. 기존 코드는 지워 기기당 유효 코드 1개
    post("/pairing-codes") {
        call.idempotent(deps) { caller ->
            // 같은 기기의 발급이 동시에 와도 코드가 둘 남지 않게
            val me = myDevice(caller, lock = true)
            if (!me.isRole(DeviceRole.PROTECTED)) throw forbidden("시니어 기기만 코드를 발급함")
            val myId = me[Devices.deviceId]
            val now = deps.now()
            PairingCodes.deleteWhere {
                (PairingCodes.protectedDeviceId eq myId) or (PairingCodes.expiresAt less now.minus(CODE_KEEP_AFTER_EXPIRY).utc())
            }
            val expiresAt = now.plus(CODE_VALID)
            // 남아 있는 다른 기기 코드(유효하거나 만료 1시간 안)와 겹치면 다시 뽑는다
            val code = generateSequence { "%06d".format(random.nextInt(1_000_000)) }.take(20).firstOrNull { c ->
                PairingCodes.insertIgnore {
                    it[PairingCodes.code] = c
                    it[protectedDeviceId] = myId
                    it[PairingCodes.expiresAt] = expiresAt.utc()
                    it[used] = false
                }.insertedCount > 0
            } ?: error("빈 연결 코드를 찾지 못함")
            reply(HttpStatusCode.Created, PairingCode(code, "phonedoctor://pair?c=$code", expiresAt))
        }
    }

    // (보호자) 코드 입력·QR 스캔 → pending 링크, 시니어에게 LINK_REQUESTED
    post("/pairing-codes/{code}/accept") {
        val code = call.parameters["code"]!!
        check400(Regex("^[0-9]{6}$").matches(code)) { "코드는 숫자 6자리" }
        withContext(Dispatchers.IO) { countAttempt(deps, call.caller) }
        call.idempotent(deps) { caller ->
            val guardian = myDevice(caller)
            if (!guardian.isRole(DeviceRole.GUARDIAN)) throw forbidden("보호자 기기만 코드를 입력함")
            val row = PairingCodes.selectAll().where { PairingCodes.code eq code }.forUpdate(ForUpdateOption.ForUpdate).singleOrNull()
                ?: throw ApiProblem(HttpStatusCode.NotFound, "pairing-code-not-found", "없는 연결 코드", "시니어 화면의 숫자를 다시 확인")
            if (row[PairingCodes.used]) throw ApiProblem(HttpStatusCode.Gone, "pairing-code-used", "이미 쓴 연결 코드", "시니어 폰에서 새 코드를 받으세요")
            if (row[PairingCodes.expiresAt].toInstant() <= deps.now()) {
                throw ApiProblem(HttpStatusCode.Gone, "pairing-code-expired", "만료된 연결 코드", "시니어 폰에서 새 코드를 받으세요")
            }
            val protectedId = row[PairingCodes.protectedDeviceId]
            val guardianId = guardian[Devices.deviceId]
            val open = Links.selectAll().where {
                (Links.protectedDeviceId eq protectedId) and (Links.guardianDeviceId eq guardianId) and (Links.status neq LinkStatus.REVOKED.db)
            }.singleOrNull()
            if (open?.get(Links.status) == LinkStatus.ACTIVE.db) {
                throw ApiProblem(HttpStatusCode.Conflict, "already-linked", "이미 연결됨")
            }
            if (activeLinkCount(protectedId) >= MAX_GUARDIANS) throw guardianLimit()
            // 확인 대기 중인 같은 쌍이 있으면(시니어가 푸시를 놓침 등) 새로 만들지 않고 다시 알린다
            val linkId = open?.get(Links.linkId) ?: UUID.randomUUID().toString().also { id ->
                Links.insert {
                    it[Links.linkId] = id
                    it[Links.protectedDeviceId] = protectedId
                    it[Links.guardianDeviceId] = guardianId
                    it[status] = LinkStatus.PENDING.db
                    it[createdAt] = deps.now().utc()
                }
            }
            PairingCodes.update({ PairingCodes.code eq code }) { it[used] = true }
            val senior = deviceRow(protectedId)
            val push = PushType.LINK_REQUESTED.to(senior[Devices.fcmToken], "linkId" to linkId, "guardianName" to guardian[Devices.displayName])
            reply(HttpStatusCode.Created, linkView(linkId), listOf(push))
        }
    }

    route("/links") {
        get {
            val status = call.request.queryParameters["status"]?.let {
                LinkStatus.of(it) ?: throw badRequest("status는 pending·active·revoked 중 하나")
            }
            val list = withContext(Dispatchers.IO) {
                transaction(deps.db) {
                    val myId = myDevice(call.caller)[Devices.deviceId]
                    var where = (Links.protectedDeviceId eq myId) or (Links.guardianDeviceId eq myId)
                    if (status != null) where = where and (Links.status eq status.db)
                    LinkList(linkViews(Links.selectAll().where(where).orderBy(Links.createdAt, SortOrder.ASC).toList()))
                }
            }
            call.respond(list)
        }

        // (시니어) "연결할게요" pending → active, 보호자에게 LINK_ACTIVATED. 이미 active면 그대로 200, 푸시 없음
        post("/{linkId}/confirm") {
            val linkId = call.parameters["linkId"]!!
            call.idempotent(deps) { caller ->
                val me = myDevice(caller, lock = true)
                val row = Links.selectAll().where { Links.linkId eq linkId }.forUpdate(ForUpdateOption.ForUpdate).singleOrNull()
                    ?: throw notFound("연결 없음")
                if (row[Links.protectedDeviceId] != me[Devices.deviceId]) throw forbidden("이 연결의 시니어 기기만 확정함")
                when (LinkStatus.of(row[Links.status])!!) {
                    LinkStatus.REVOKED -> throw ApiProblem(HttpStatusCode.Conflict, "link-revoked", "이미 해제된 연결", "보호자가 코드를 다시 입력해야 함")
                    LinkStatus.ACTIVE -> reply(HttpStatusCode.OK, linkView(linkId))
                    LinkStatus.PENDING -> {
                        // 시니어 기기 행을 잠갔으므로 여러 연결을 동시에 확정해도 상한을 넘지 않는다
                        if (activeLinkCount(me[Devices.deviceId]) >= MAX_GUARDIANS) throw guardianLimit()
                        Links.update({ Links.linkId eq linkId }) {
                            it[status] = LinkStatus.ACTIVE.db
                            it[activatedAt] = deps.now().utc()
                        }
                        val guardian = deviceRow(row[Links.guardianDeviceId])
                        val push = PushType.LINK_ACTIVATED.to(guardian[Devices.fcmToken], "linkId" to linkId, "protectedName" to me[Devices.displayName])
                        reply(HttpStatusCode.OK, linkView(linkId), listOf(push))
                    }
                }
            }
        }

        // 연결 해제 (양쪽 모두). 상대에게 LINK_REVOKED. 이미 해제됐으면 204, 푸시 없음 (재전송)
        delete("/{linkId}") {
            val linkId = call.parameters["linkId"]!!
            val pushes = withContext(Dispatchers.IO) {
                transaction(deps.db) {
                    val me = myDevice(call.caller)
                    val row = Links.selectAll().where { Links.linkId eq linkId }.forUpdate(ForUpdateOption.ForUpdate).singleOrNull()
                        ?: throw notFound("연결 없음")
                    val myId = me[Devices.deviceId]
                    if (myId != row[Links.protectedDeviceId] && myId != row[Links.guardianDeviceId]) throw forbidden("내 연결이 아님")
                    if (row[Links.status] == LinkStatus.REVOKED.db) return@transaction emptyList()
                    Links.update({ Links.linkId eq linkId }) {
                        it[status] = LinkStatus.REVOKED.db
                        it[revokedAt] = deps.now().utc()
                    }
                    // TODO(alerts): 이 연결로 만든 요청 데이터 즉시 삭제 (openapi revokeLink)
                    listOf(revokedPush(row, byDeviceId = myId))
                }
            }
            withContext(Dispatchers.IO) { deps.sendAll(pushes) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** [byDeviceId]가 링크 [row]를 끊었음을 상대 기기에 알리는 푸시. 이름 키는 보낸 쪽 역할 (LINK_REQUESTED·ACTIVATED와 같음) */
private fun revokedPush(row: ResultRow, byDeviceId: String): Push {
    val bySenior = byDeviceId == row[Links.protectedDeviceId]
    val other = deviceRow(if (bySenior) row[Links.guardianDeviceId] else row[Links.protectedDeviceId])
    val by = deviceRow(byDeviceId)
    val nameKey = if (bySenior) "protectedName" else "guardianName"
    return PushType.LINK_REVOKED.to(other[Devices.fcmToken], "linkId" to row[Links.linkId], nameKey to by[Devices.displayName])
}

/** 기기를 지우기 전, 해제되지 않은 연결의 상대 기기들에 보낼 LINK_REVOKED (링크 행은 외래 키 연쇄로 함께 지워짐) */
fun revokedPushesOnDelete(deviceId: String): List<Push> =
    Links.selectAll().where {
        ((Links.protectedDeviceId eq deviceId) or (Links.guardianDeviceId eq deviceId)) and (Links.status neq LinkStatus.REVOKED.db)
    }.map { revokedPush(it, byDeviceId = deviceId) }
