package io.github.inq_o.phonedoctor.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

// openapi.yaml components/schemas 의 devices 부분

@Serializable
enum class DeviceRole(val db: String) {
    @SerialName("protected") PROTECTED("protected"),
    @SerialName("guardian") GUARDIAN("guardian"),
    ;

    companion object {
        fun of(db: String) = entries.first { it.db == db }
    }
}

@Serializable
data class DeviceRegistration(
    val role: DeviceRole,
    val fcmToken: String,
    val displayName: String? = null,
    val appVersionCode: Int,
    val sdkInt: Int,
    val manufacturer: String? = null,
    val locale: String? = null,
)

@Serializable
data class DeviceUpdate(
    val fcmToken: String? = null,
    val displayName: String? = null,
    val appVersionCode: Int? = null,
    val notifyCaution: Boolean? = null,
)

@Serializable
data class ProtectionState(
    val notifications: Boolean,
    val usageAccess: Boolean,
    val notificationListener: Boolean,
)

@Serializable
data class DeviceStatus(val checkedAt: Ts, val protection: ProtectionState)

@Serializable
data class Device(
    val deviceId: String,
    val role: DeviceRole,
    val displayName: String,
    val notifyCaution: Boolean,
    val createdAt: Ts,
)

private const val NAME_MAX = 20
private const val SENIOR_DEFAULT_NAME = "부모님"
private const val GUARDIAN_DEFAULT_NAME = "보호자"

fun ResultRow.toDevice() = Device(
    deviceId = this[Devices.deviceId],
    role = DeviceRole.of(this[Devices.role]),
    displayName = this[Devices.displayName],
    notifyCaution = this[Devices.notifyCaution],
    createdAt = this[Devices.createdAt].toInstant(),
)

private fun checkName(name: String?) =
    check400(name == null || name.trim().length in 1..NAME_MAX) { "displayName은 1~${NAME_MAX}자" }

private fun checkToken(token: String?) = check400(token == null || token.isNotBlank()) { "fcmToken이 비어 있음" }

/** 경로의 기기를 읽는다. 없으면 404, 요청자 기기가 아니면 403 */
fun ownDevice(caller: Caller, deviceId: String, lock: Boolean = false): ResultRow {
    val query = Devices.selectAll().where { Devices.deviceId eq deviceId }
    val row = (if (lock) query.forUpdate(ForUpdateOption.ForUpdate) else query).singleOrNull() ?: throw notFound("기기 없음")
    if (row[Devices.firebaseUid] != caller.uid) throw forbidden("내 기기가 아님")
    return row
}

fun Route.deviceRoutes(deps: Deps) {
    route("/devices") {
        // 기기 등록. 같은 Firebase 사용자가 다시 등록하면(앱 데이터 유지한 채 다시 실행 등) 그 기기를 갱신해 돌려준다
        post {
            val body = call.receive<DeviceRegistration>()
            checkToken(body.fcmToken)
            checkName(body.displayName)
            call.idempotent(deps) { caller ->
                // 보호자는 Google 로그인 사용자만 (시니어는 익명). 익명 사용자가 보호자로 연결 요청을 받지 못하게
                if (body.role == DeviceRole.GUARDIAN && caller.signInProvider == "anonymous") {
                    throw ApiProblem(HttpStatusCode.Forbidden, "guardian-requires-account", "보호자는 Google 로그인이 필요함")
                }
                val defaultName = when (body.role) {
                    DeviceRole.PROTECTED -> SENIOR_DEFAULT_NAME
                    DeviceRole.GUARDIAN -> caller.name?.trim()?.take(NAME_MAX)?.ifEmpty { null } ?: GUARDIAN_DEFAULT_NAME
                }
                // 없으면 만들고, 있으면(다른 키로 동시에 온 등록이 먼저 만든 경우 포함) 아무것도 안 함 → 아래에서 갱신
                val created = Devices.insertIgnore {
                    it[deviceId] = UUID.randomUUID().toString()
                    it[firebaseUid] = caller.uid
                    it[role] = body.role.db
                    it[displayName] = body.displayName?.trim() ?: defaultName
                    it[fcmToken] = body.fcmToken
                    it[appVersionCode] = body.appVersionCode
                    it[sdkInt] = body.sdkInt
                    it[manufacturer] = body.manufacturer
                    it[locale] = body.locale
                    it[notifyCaution] = false
                    it[createdAt] = deps.now().utc()
                }.insertedCount > 0
                val existing = Devices.selectAll().where { Devices.firebaseUid eq caller.uid }.single()
                if (existing[Devices.role] != body.role.db) {
                    throw ApiProblem(HttpStatusCode.Conflict, "role-mismatch", "이미 다른 역할로 등록됨", "역할을 바꾸려면 앱 데이터를 지우고 다시 등록")
                }
                if (!created) {
                    Devices.update({ Devices.deviceId eq existing[Devices.deviceId] }) {
                        it[fcmToken] = body.fcmToken
                        it[appVersionCode] = body.appVersionCode
                        it[sdkInt] = body.sdkInt
                        it[manufacturer] = body.manufacturer
                        it[locale] = body.locale
                        body.displayName?.let { n -> it[displayName] = n.trim() }
                    }
                }
                val row = Devices.selectAll().where { Devices.firebaseUid eq caller.uid }.single()
                reply(HttpStatusCode.Created, row.toDevice())
            }
        }

        route("/{deviceId}") {
            patch {
                val id = call.parameters["deviceId"]!!
                val body = call.receive<DeviceUpdate>()
                check400(body != DeviceUpdate()) { "바꿀 값이 없음" }
                checkToken(body.fcmToken)
                checkName(body.displayName)
                val device = withContext(Dispatchers.IO) {
                    transaction(deps.db) {
                        val row = ownDevice(call.caller, id)
                        check400(body.notifyCaution == null || row[Devices.role] == DeviceRole.GUARDIAN.db) { "notifyCaution은 보호자 기기만" }
                        Devices.update({ Devices.deviceId eq id }) {
                            body.fcmToken?.let { v -> it[fcmToken] = v }
                            body.displayName?.let { v -> it[displayName] = v.trim() }
                            body.appVersionCode?.let { v -> it[appVersionCode] = v }
                            body.notifyCaution?.let { v -> it[notifyCaution] = v }
                        }
                        Devices.selectAll().where { Devices.deviceId eq id }.single().toDevice()
                    }
                }
                call.respond(device)
            }

            // 앱 안의 "모든 데이터 지우기". 연결·요청은 DB의 외래 키 연쇄 삭제로 함께 지우고, 연결 상대에게 LINK_REVOKED.
            // 이미 없으면 204 (재전송)
            delete {
                val id = call.parameters["deviceId"]!!
                val pushes = withContext(Dispatchers.IO) {
                    transaction(deps.db) {
                        // 같은 기기 삭제가 동시에 와도 LINK_REVOKED가 한 번만 가게 행을 잠근다. 뒤 요청은 행이 없어 빈 목록
                        val row = Devices.selectAll().where { Devices.deviceId eq id }
                            .forUpdate(ForUpdateOption.ForUpdate).singleOrNull() ?: return@transaction emptyList()
                        if (row[Devices.firebaseUid] != call.caller.uid) throw forbidden("내 기기가 아님")
                        // 보호자 기기면 연결이 지워진 뒤 받은 연결이 없어진 요청도 지운다 (시니어 기기면 요청은 외래 키 연쇄로 지워짐)
                        val seniors = Links.select(Links.protectedDeviceId).where { Links.guardianDeviceId eq id }.map { it[Links.protectedDeviceId] }
                        revokedPushesOnDelete(id).also {
                            Devices.deleteWhere { Devices.deviceId eq id }
                            deleteOrphanAlerts(seniors)
                        }
                    }
                }
                withContext(Dispatchers.IO) { deps.sendAll(pushes) }
                call.respond(HttpStatusCode.NoContent)
            }

            // (시니어) 검사 완료·보호 상태 보고. 판단 규칙은 acceptStatus
            put("/status") {
                val id = call.parameters["deviceId"]!!
                val body = call.receive<DeviceStatus>()
                withContext(Dispatchers.IO) {
                    transaction(deps.db) {
                        // 같은 기기의 보고가 동시에 와도 하나씩 판단하도록 행을 잠근다
                        val row = ownDevice(call.caller, id, lock = true)
                        if (row[Devices.role] != DeviceRole.PROTECTED.db) throw forbidden("시니어 기기만 상태를 보고함")
                        val now = deps.now()
                        val maxReported = row[Devices.checkedReportedAt]?.toInstant()
                        val last = row[Devices.lastCheckedAt]?.toInstant()
                        // 비교와 저장에 같은 값을 쓴다. 받은 값(나노초까지)을 그대로 비교하면 DB(마이크로초)에 잘려 저장된 값보다 커 보여
                        // 같은 본문 재전송이 새 보고로 받아들여진다. 서버 시각(deps.now)과 같은 ms로 자른다
                        val checkedAt = body.checkedAt.truncatedTo(ChronoUnit.MILLIS)
                        if (acceptStatus(checkedAt, maxReported, last, now)) {
                            Devices.update({ Devices.deviceId eq id }) {
                                it[checkedReportedAt] = maxOf(maxReported ?: checkedAt, checkedAt).utc()
                                it[lastCheckedAt] = minOf(checkedAt, now).utc()
                                it[protection] = body.protection
                            }
                        }
                    }
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}

/**
 * 상태 보고를 반영할지.
 * - [maxReported]: 지금까지 받은 보고의 기기 시각 중 가장 큰 값. 이보다 크면 새 보고
 * - 같거나 작으면 순서가 뒤바뀐 옛 보고·같은 본문 재전송이라 무시
 * - 예외: 기기 시계가 앞서 있다가 바로잡힌 경우(maxReported가 서버 시각보다 미래, 이번 보고는 서버 시각 이하).
 *   이때도 저장된 [last]보다 오래된 보고는 받지 않아 마지막 검사 시각이 뒤로 가지 않는다.
 *   maxReported는 줄이지 않으므로, 바로잡힌 뒤 옛 미래 시각 본문이 재전송돼도 새 보고로 보지 않는다
 * 저장하는 lastCheckedAt은 서버 시각을 넘지 않게 잘라 기기 시계가 앞서도 F6 48시간 판정이 밀리지 않게 한다
 */
fun acceptStatus(checkedAt: Instant, maxReported: Instant?, last: Instant?, now: Instant): Boolean = when {
    maxReported == null || checkedAt > maxReported -> true
    maxReported > now && checkedAt <= now -> last == null || checkedAt >= last
    else -> false
}
