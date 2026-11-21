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
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
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
fun ownDevice(caller: Caller, deviceId: String): ResultRow {
    val row = Devices.selectAll().where { Devices.deviceId eq deviceId }.singleOrNull() ?: throw notFound("기기 없음")
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

            // 앱 안의 "모든 데이터 지우기". 연결·요청은 DB의 외래 키 연쇄 삭제로 함께 지운다. 이미 없으면 204 (재전송)
            delete {
                val id = call.parameters["deviceId"]!!
                withContext(Dispatchers.IO) {
                    transaction(deps.db) {
                        val row = Devices.selectAll().where { Devices.deviceId eq id }.singleOrNull()
                        if (row != null) {
                            if (row[Devices.firebaseUid] != call.caller.uid) throw forbidden("내 기기가 아님")
                            Devices.deleteWhere { Devices.deviceId eq id }
                        }
                    }
                }
                call.respond(HttpStatusCode.NoContent)
            }

            // (시니어) 검사 완료·보호 상태 보고.
            // 새 보고인지는 기기가 보낸 시각(checkedReportedAt)끼리 비교한다: 순서가 뒤바뀐 옛 보고·같은 본문 재전송은 무시.
            // 저장하는 lastCheckedAt은 서버 시각을 넘지 않게 자른다(기기 시계가 앞서도 F6 48시간 판정이 밀리지 않게).
            // 앞서 있던 기기 시계가 바로잡히면(저장된 기기 시각이 서버 시각보다 미래, 새 보고는 아님) 그 보고를 받는다
            put("/status") {
                val id = call.parameters["deviceId"]!!
                val body = call.receive<DeviceStatus>()
                withContext(Dispatchers.IO) {
                    transaction(deps.db) {
                        val row = ownDevice(call.caller, id)
                        if (row[Devices.role] != DeviceRole.PROTECTED.db) throw forbidden("시니어 기기만 상태를 보고함")
                        val now = deps.now()
                        val reported = body.checkedAt.utc()
                        val r = Devices.checkedReportedAt
                        var newer: Op<Boolean> = r.isNull() or (r less reported)
                        if (body.checkedAt <= now) newer = newer or (r greater now.utc())
                        Devices.update({ (Devices.deviceId eq id) and newer }) {
                            it[checkedReportedAt] = reported
                            it[lastCheckedAt] = minOf(body.checkedAt, now).utc()
                            it[protection] = body.protection
                        }
                    }
                }
                call.respond(HttpStatusCode.NoContent)
            }
        }
    }
}
