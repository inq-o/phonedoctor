package io.github.inq_o.phonedoctor.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.not
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.batchInsert
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Base64
import java.util.UUID

// openapi.yaml 의 alerts 부분 (POST·GET /alerts, GET /alerts/{id}, /decision, /outcome)

@Serializable
enum class RiskLevel { SAFE, CAUTION, RISK }

@Serializable
enum class InstallSource { GOOGLE_PLAY, ONE_STORE, GALAXY_STORE, OTHER_STORE, BROWSER_OR_FILE, ADB, UNKNOWN }

/** contracts/classifier.md §4 근거 코드 표 */
@Serializable
enum class ReasonCode {
    AD_SDK_HEAVY, OVERLAY_PERMISSION, LAUNCHER_ICON_HIDDEN, SIDELOADED, DEVICE_ADMIN, ACCESSIBILITY_SERVICE,
    NOTIFICATION_LISTENER, SMS_CALL_ACCESS, INSTALL_OTHER_APPS, BOOT_AUTOSTART, IMPERSONATION,
    FREQUENT_BACKGROUND_LAUNCH, CLEANER_BOOSTER_PATTERN,
}

@Serializable
data class Reason(val code: ReasonCode, val weight: Double, val evidence: JsonObject? = null)

@Serializable
data class Verdict(val level: RiskLevel, val score: Double, val modelVersion: String, val reasons: List<Reason>)

@Serializable
data class AppInfo(
    val packageName: String,
    val appLabel: String,
    val versionCode: Long,
    val versionName: String? = null,
    val signingCertSha256: String,
    val installSource: InstallSource,
    val installerPackage: String? = null,
    val firstInstallTime: Ts,
    val lastUpdateTime: Ts? = null,
    val iconPngBase64: String? = null,
)

@Serializable
enum class AlertSource(val db: String) {
    @SerialName("install_scan") INSTALL_SCAN("install_scan"),
    @SerialName("symptom") SYMPTOM("symptom"),
    ;

    companion object {
        fun of(db: String) = entries.first { it.db == db }
    }
}

@Serializable
enum class AlertKind(val db: String) {
    @SerialName("approval") APPROVAL("approval"),
    @SerialName("notice") NOTICE("notice"),
    ;

    companion object {
        fun of(db: String) = entries.first { it.db == db }
    }
}

@Serializable
data class SymptomEvidence(
    /** 시니어 기기 현지 날짜 YYYY-MM-DD */
    val date: String,
    val count: Int,
    val days: Int,
    val firstAt: Ts,
    val lastAt: Ts,
    val detectorVersion: String,
)

@Serializable
data class AlertCreate(
    val source: AlertSource,
    val kind: AlertKind = AlertKind.APPROVAL,
    val app: AppInfo,
    val verdict: Verdict? = null,
    val symptom: SymptomEvidence? = null,
    val detectedAt: Ts,
)

@Serializable
enum class AlertStatus(val db: String) {
    @SerialName("open") OPEN("open"),
    @SerialName("decided") DECIDED("decided"),
    @SerialName("resolved") RESOLVED("resolved"),
    @SerialName("expired") EXPIRED("expired"),
    ;

    companion object {
        fun of(db: String) = entries.firstOrNull { it.db == db }
    }
}

@Serializable
enum class DecisionType(val db: String) {
    @SerialName("recommend_uninstall") RECOMMEND_UNINSTALL("recommend_uninstall"),
    @SerialName("allow") ALLOW("allow"),
    ;

    companion object {
        fun of(db: String) = entries.first { it.db == db }
    }
}

@Serializable
data class DecisionCreate(val decision: DecisionType, val messageToSenior: String? = null)

@Serializable
data class Decision(val decision: DecisionType, val messageToSenior: String? = null, val decidedBy: DeviceSummary, val decidedAt: Ts)

@Serializable
enum class OutcomeType(val db: String) {
    @SerialName("uninstalled") UNINSTALLED("uninstalled"),
    @SerialName("declined") DECLINED("declined"),
    @SerialName("already_removed") ALREADY_REMOVED("already_removed"),
    @SerialName("suspended") SUSPENDED("suspended"),
    @SerialName("allowed") ALLOWED("allowed"),
    ;

    companion object {
        fun of(db: String) = entries.first { it.db == db }
    }
}

@Serializable
data class OutcomeCreate(val outcome: OutcomeType, val occurredAt: Ts)

@Serializable
data class Outcome(val outcome: OutcomeType, val occurredAt: Ts)

@Serializable
data class Alert(
    val alertId: String,
    val protectedDevice: DeviceSummary,
    val linkIds: List<String>,
    val status: AlertStatus,
    val source: AlertSource,
    val kind: AlertKind,
    val app: AppInfo,
    val verdict: Verdict? = null,
    val symptom: SymptomEvidence? = null,
    val decision: Decision? = null,
    val outcome: Outcome? = null,
    val detectedAt: Ts,
    val createdAt: Ts,
    val updatedAt: Ts,
    val remindAt: Ts? = null,
)

@Serializable
data class AlertList(val items: List<Alert>, val nextPageToken: String? = null)

private val REMIND_AFTER = Duration.ofHours(24)
private val EXPIRE_AFTER = Duration.ofDays(7)
private val DELETE_AFTER = Duration.ofDays(90)
private const val MESSAGE_MAX = 40
private const val LABEL_MAX = 60
private const val ICON_MAX = 20_000
private const val PAGE_DEFAULT = 20
private const val PAGE_MAX = 50
private val SHA256 = Regex("^[0-9a-f]{64}$")

/** openapi 스키마 제약 중 직렬화로 걸러지지 않는 것 (조건부 필수, 길이·범위) */
private fun AlertCreate.validate() {
    check400(source != AlertSource.INSTALL_SCAN || verdict != null) { "install_scan은 verdict 필수" }
    check400(source != AlertSource.SYMPTOM || symptom != null) { "symptom은 symptom 근거 필수" }
    check400(kind != AlertKind.NOTICE || source == AlertSource.SYMPTOM) { "notice는 source = symptom만" }
    check400(verdict?.level != RiskLevel.SAFE || source != AlertSource.INSTALL_SCAN) { "안전 판정은 보내지 않음" }
    check400(app.packageName.isNotBlank()) { "packageName이 비어 있음" }
    check400(app.appLabel.isNotBlank() && app.appLabel.length <= LABEL_MAX) { "appLabel은 1~${LABEL_MAX}자" }
    check400(SHA256.matches(app.signingCertSha256)) { "signingCertSha256은 소문자 16진수 64자" }
    check400((app.iconPngBase64?.length ?: 0) <= ICON_MAX) { "iconPngBase64는 ${ICON_MAX}자 이하" }
    verdict?.let { v ->
        check400(v.score in 0.0..1.0) { "score는 0~1" }
        check400(v.reasons.size <= 3) { "reasons는 3개 이하" }
        check400(v.reasons.all { it.weight in 0.0..1.0 }) { "weight는 0~1" }
    }
    symptom?.let { s ->
        check400(s.count >= 1 && s.days >= 1) { "count·days는 1 이상" }
        check400(runCatching { LocalDate.parse(s.date) }.isSuccess) { "date는 YYYY-MM-DD" }
    }
}

/** 증상 근거가 더 최신인지: (date, count) 순서로 비교 (openapi createAlert) */
private fun SymptomEvidence.newerThan(other: SymptomEvidence): Boolean {
    val d = LocalDate.parse(date).compareTo(LocalDate.parse(other.date))
    return d > 0 || (d == 0 && count > other.count)
}

private fun alertRow(alertId: String, lock: Boolean = false): ResultRow? {
    val q = Alerts.selectAll().where { Alerts.alertId eq alertId }
    return (if (lock) q.forUpdate(ForUpdateOption.ForUpdate) else q).singleOrNull()
}

private val alertLinkJoin get() = AlertLinks.join(Links, JoinType.INNER, AlertLinks.linkId, Links.linkId)

/** 요청을 받은 연결 중 지금 active인 것: (linkId, 보호자 기기 ID) */
fun activeRecipients(alertId: String): List<Pair<String, String>> =
    alertLinkJoin.select(Links.linkId, Links.guardianDeviceId)
        .where { (AlertLinks.alertId eq alertId) and (Links.status eq LinkStatus.ACTIVE.db) }
        .map { it[Links.linkId] to it[Links.guardianDeviceId] }

/** 보호자 [guardianId]가 볼 수 있는 요청 ID (active 연결로 받은 것) */
private fun visibleToGuardian(guardianId: String) =
    alertLinkJoin.select(AlertLinks.alertId)
        .where { (Links.guardianDeviceId eq guardianId) and (Links.status eq LinkStatus.ACTIVE.db) }

private fun canSee(me: ResultRow, alert: ResultRow): Boolean {
    val myId = me[Devices.deviceId]
    return if (me[Devices.role] == DeviceRole.PROTECTED.db) {
        alert[Alerts.protectedDeviceId] == myId
    } else {
        activeRecipients(alert[Alerts.alertId]).any { it.second == myId }
    }
}

private fun ResultRow.decisionOf(): Decision? = this[Alerts.decision]?.let {
    Decision(
        decision = DecisionType.of(it),
        messageToSenior = this[Alerts.messageToSenior],
        decidedBy = DeviceSummary(this[Alerts.decidedById]!!, this[Alerts.decidedByName]!!),
        decidedAt = this[Alerts.decidedAt]!!.toInstant(),
    )
}

private fun alertViews(rows: List<ResultRow>): List<Alert> {
    if (rows.isEmpty()) return emptyList()
    val ids = rows.map { it[Alerts.alertId] }
    val links = AlertLinks.selectAll().where { AlertLinks.alertId inList ids }
        .groupBy({ it[AlertLinks.alertId] }, { it[AlertLinks.linkId] })
    val deviceIds = rows.map { it[Alerts.protectedDeviceId] }.distinct()
    val names = Devices.select(Devices.deviceId, Devices.displayName).where { Devices.deviceId inList deviceIds }
        .associate { it[Devices.deviceId] to it[Devices.displayName] }
    return rows.map { r ->
        val pid = r[Alerts.protectedDeviceId]
        Alert(
            alertId = r[Alerts.alertId],
            protectedDevice = DeviceSummary(pid, names.getValue(pid)),
            linkIds = links[r[Alerts.alertId]].orEmpty().sorted(),
            status = AlertStatus.of(r[Alerts.status])!!,
            source = AlertSource.of(r[Alerts.alertSource]),
            kind = AlertKind.of(r[Alerts.kind]),
            app = r[Alerts.app],
            verdict = r[Alerts.verdict],
            symptom = r[Alerts.symptom],
            decision = r.decisionOf(),
            outcome = r[Alerts.outcome]?.let { Outcome(OutcomeType.of(it), r[Alerts.outcomeAt]!!.toInstant()) },
            detectedAt = r[Alerts.detectedAt].toInstant(),
            createdAt = r[Alerts.createdAt].toInstant(),
            updatedAt = r[Alerts.updatedAt].toInstant(),
            remindAt = r[Alerts.remindAt]?.toInstant(),
        )
    }
}

fun alertView(alertId: String) = alertViews(listOf(alertRow(alertId)!!)).single()

fun fcmToken(deviceId: String) =
    Devices.select(Devices.fcmToken).where { Devices.deviceId eq deviceId }.single()[Devices.fcmToken]

/**
 * 보호자에게 요청을 알리는 푸시 (ALERT_CREATED·ALERT_NOTICE, 재알림은 ALERT_REMINDER).
 * 문구 고르는 값만 담는다 (push-messages.md §2)
 */
fun alertPush(alert: Alert, guardianId: String, reminder: Boolean = false): Push {
    val type = when {
        reminder -> PushType.ALERT_REMINDER
        alert.kind == AlertKind.NOTICE -> PushType.ALERT_NOTICE
        else -> PushType.ALERT_CREATED
    }
    val data = buildList {
        add("alertId" to alert.alertId)
        add("protectedName" to alert.protectedDevice.displayName)
        add("appLabel" to alert.app.appLabel)
        add("source" to alert.source.db)
        alert.symptom?.let {
            add("symptomCount" to it.count.toString())
            add("symptomDate" to it.date)
        }
    }
    return type.to(fcmToken(guardianId), *data.toTypedArray())
}

/**
 * 이번 보고를 받을 active 연결: (linkId, 보호자 기기 ID).
 * 설치 시점 "주의" 판정은 그 보호자가 "주의 단계도 받기"를 켰을 때만 (FR-028)
 */
private fun eligibleLinks(protectedId: String, body: AlertCreate, existingSymptom: SymptomEvidence?): List<Pair<String, String>> {
    // 열린 요청에 이미 증상 근거가 있으면 "주의"만 다시 와도 주의 단계 설정과 상관없이 모두 받는다
    val cautionOnly = body.symptom == null && existingSymptom == null && body.verdict?.level == RiskLevel.CAUTION
    return Links.join(Devices, JoinType.INNER, Links.guardianDeviceId, Devices.deviceId)
        .select(Links.linkId, Links.guardianDeviceId, Devices.notifyCaution)
        .where { (Links.protectedDeviceId eq protectedId) and (Links.status eq LinkStatus.ACTIVE.db) }
        .filter { !cautionOnly || it[Devices.notifyCaution] }
        .map { it[Links.linkId] to it[Links.guardianDeviceId] }
}

/** 받은 연결이 하나도 남지 않은 요청을 지운다 (연결 해제 즉시 삭제, NFR-09) */
fun deleteOrphanAlerts(protectedIds: Collection<String>) {
    if (protectedIds.isEmpty()) return
    Alerts.deleteWhere {
        (Alerts.protectedDeviceId inList protectedIds) and
            not(exists(AlertLinks.select(AlertLinks.alertId).where { AlertLinks.alertId eq Alerts.alertId }))
    }
}

private fun alertNotFound() = notFound("요청 없음")

private fun encodeToken(updatedAt: Instant, alertId: String) =
    Base64.getUrlEncoder().withoutPadding().encodeToString("$updatedAt|$alertId".toByteArray())

private fun decodeToken(token: String): Pair<Instant, String> = try {
    val (t, id) = String(Base64.getUrlDecoder().decode(token)).split('|', limit = 2)
    Instant.parse(t) to id
} catch (_: Exception) {
    throw badRequest("pageToken이 올바르지 않음")
}

fun Route.alertRoutes(deps: Deps) {
    route("/alerts") {
        // (시니어) 승인 요청 보고. 같은 앱의 열린 요청이 있으면 근거만 합치고 200
        post {
            val body = call.receive<AlertCreate>()
            body.validate()
            call.idempotent(deps) { caller ->
                // 같은 기기의 보고를 하나씩 처리해 같은 앱의 열린 요청이 둘 생기지 않게
                val me = myDevice(caller, lock = true)
                if (me[Devices.role] != DeviceRole.PROTECTED.db) throw forbidden("시니어 기기만 요청을 보냄")
                val myId = me[Devices.deviceId]
                val now = deps.now()
                val activeCount = Links.selectAll()
                    .where { (Links.protectedDeviceId eq myId) and (Links.status eq LinkStatus.ACTIVE.db) }.count()
                if (activeCount == 0L) throw ApiProblem(HttpStatusCode.Conflict, "no-active-link", "연결된 보호자 없음")
                // 응답(decision)과 같은 행에서 하나씩: 응답이 먼저 커밋됐으면 open이 아니어서 새 요청을 만든다
                val open = Alerts.selectAll().where {
                    (Alerts.protectedDeviceId eq myId) and (Alerts.packageName eq body.app.packageName) and
                        (Alerts.signingCertSha256 eq body.app.signingCertSha256) and (Alerts.versionCode eq body.app.versionCode) and
                        (Alerts.status eq AlertStatus.OPEN.db)
                }.forUpdate(ForUpdateOption.ForUpdate).singleOrNull()
                // 받는 연결 읽기: 연결 해제도 시니어 기기 행을 먼저 잠그므로(Pairing.kt) 여기서 읽은 active 연결은 커밋까지 그대로다
                val eligible = eligibleLinks(myId, body, open?.get(Alerts.symptom))

                if (open == null) {
                    if (eligible.isEmpty()) {
                        throw ApiProblem(HttpStatusCode.Conflict, "no-recipient", "받을 보호자 없음", "\"주의\" 판정은 주의 단계도 받기를 켠 보호자에게만 감")
                    }
                    val alertId = UUID.randomUUID().toString()
                    Alerts.insert {
                        it[Alerts.alertId] = alertId
                        it[protectedDeviceId] = myId
                        it[status] = AlertStatus.OPEN.db
                        it[alertSource] = body.source.db
                        it[kind] = body.kind.db
                        it[packageName] = body.app.packageName
                        it[signingCertSha256] = body.app.signingCertSha256
                        it[versionCode] = body.app.versionCode
                        it[app] = body.app
                        it[verdict] = body.verdict
                        it[symptom] = body.symptom
                        it[detectedAt] = body.detectedAt.utc()
                        it[createdAt] = now.utc()
                        it[updatedAt] = now.utc()
                        it[remindAt] = if (body.kind == AlertKind.APPROVAL) now.plus(REMIND_AFTER).utc() else null
                        it[expiresAt] = now.plus(EXPIRE_AFTER).utc()
                        it[deleteAt] = now.plus(DELETE_AFTER).utc()
                    }
                    AlertLinks.batchInsert(eligible) { (linkId, _) ->
                        this[AlertLinks.alertId] = alertId
                        this[AlertLinks.linkId] = linkId
                    }
                    val view = alertView(alertId)
                    return@idempotent reply(HttpStatusCode.Created, view, eligible.map { (_, g) -> alertPush(view, g) }, alertId)
                }

                // 열린 요청에 합치기: 없던 근거는 붙이고, 증상 근거는 더 최신일 때만, 판정은 단계가 올라갈 때만 바꾼다. source·kind는 처음 값
                val alertId = open[Alerts.alertId]
                // 판정은 없던 것을 붙이거나 단계가 올라갈 때(주의 → 위험)만 바꾼다. 다시 내려가거나 같은 단계면 처음 값
                val oldVerdict = open[Alerts.verdict]
                val newVerdict = body.verdict?.takeIf { oldVerdict == null || it.level > oldVerdict.level }
                val oldSymptom = open[Alerts.symptom]
                val newSymptom = body.symptom?.takeIf { oldSymptom == null || it.newerThan(oldSymptom) }
                val already = AlertLinks.select(AlertLinks.linkId).where { AlertLinks.alertId eq alertId }.map { it[AlertLinks.linkId] }.toSet()
                // 요청 뒤에 연결됐거나 이번 근거로 받을 대상이 된 보호자 (예: "주의" 요청에 증상 근거가 붙음)
                val added = eligible.filter { it.first !in already }
                val evidenceChanged = newVerdict != null || newSymptom != null
                if (evidenceChanged || added.isNotEmpty()) {
                    Alerts.update({ Alerts.alertId eq alertId }) {
                        if (newVerdict != null) it[verdict] = newVerdict
                        if (newSymptom != null) it[symptom] = newSymptom
                        it[updatedAt] = now.utc()
                    }
                    AlertLinks.batchInsert(added) { (linkId, _) ->
                        this[AlertLinks.alertId] = alertId
                        this[AlertLinks.linkId] = linkId
                    }
                }
                val view = alertView(alertId)
                val addedGuardians = added.map { it.second }.toSet()
                val pushes = added.map { (_, g) -> alertPush(view, g) } +
                    if (evidenceChanged) {
                        activeRecipients(alertId).filter { it.second !in addedGuardians }
                            .map { (_, g) -> PushType.ALERT_UPDATED.to(fcmToken(g), "alertId" to alertId) }
                    } else {
                        emptyList()
                    }
                reply(HttpStatusCode.OK, view, pushes, alertId)
            }
        }

        // 요청 목록 (푸시 누락 대비 당겨 오기). 바뀐 순서(updatedAt, alertId)로 주고, 다음 쪽은 nextPageToken
        get {
            val q = call.request.queryParameters
            val status = q["status"]?.let { AlertStatus.of(it) ?: throw badRequest("status는 open·decided·resolved·expired 중 하나") }
            val since = q["updatedSince"]?.let {
                try {
                    Instant.parse(it)
                } catch (_: DateTimeParseException) {
                    throw badRequest("updatedSince는 RFC 3339 시각")
                }
            }
            val size = q["pageSize"]?.let { it.toIntOrNull() ?: throw badRequest("pageSize는 정수") } ?: PAGE_DEFAULT
            check400(size in 1..PAGE_MAX) { "pageSize는 1~$PAGE_MAX" }
            val after = q["pageToken"]?.let { decodeToken(it) }
            val list = withContext(Dispatchers.IO) {
                transaction(deps.db) {
                    val me = myDevice(call.caller)
                    val myId = me[Devices.deviceId]
                    var where: Op<Boolean> = if (me[Devices.role] == DeviceRole.PROTECTED.db) {
                        Alerts.protectedDeviceId eq myId
                    } else {
                        Alerts.alertId inSubQuery visibleToGuardian(myId)
                    }
                    if (status != null) where = where and (Alerts.status eq status.db)
                    // 같은 시각 포함: 같은 ms에 바뀐 다른 요청을 놓치지 않게. 이미 받은 요청이 다시 올 수 있어 앱은 alertId로 덮어쓴다
                    if (since != null) where = where and (Alerts.updatedAt greaterEq since.utc())
                    if (after != null) {
                        val (t, id) = after
                        where = where and ((Alerts.updatedAt greater t.utc()) or ((Alerts.updatedAt eq t.utc()) and (Alerts.alertId greater id)))
                    }
                    val rows = Alerts.selectAll().where(where)
                        .orderBy(Alerts.updatedAt to SortOrder.ASC, Alerts.alertId to SortOrder.ASC)
                        .limit(size + 1).toList()
                    val page = rows.take(size)
                    val next = if (rows.size > size) page.last().let { encodeToken(it[Alerts.updatedAt].toInstant(), it[Alerts.alertId]) } else null
                    AlertList(alertViews(page), next)
                }
            }
            call.respond(list)
        }

        route("/{alertId}") {
            get {
                val alertId = call.parameters["alertId"]!!
                val alert = withContext(Dispatchers.IO) {
                    transaction(deps.db) {
                        val me = myDevice(call.caller)
                        val row = alertRow(alertId) ?: throw alertNotFound()
                        // 볼 수 없는 요청은 있는지도 알리지 않는다
                        if (!canSee(me, row)) throw alertNotFound()
                        alertViews(listOf(row)).single()
                    }
                }
                call.respond(alert)
            }

            // (보호자) 응답. 먼저 도착한 응답만 유효 (FR-024)
            post("/decision") {
                val alertId = call.parameters["alertId"]!!
                val body = call.receive<DecisionCreate>()
                val message = body.messageToSenior?.trim()?.ifEmpty { null }
                check400((message?.length ?: 0) <= MESSAGE_MAX) { "messageToSenior는 ${MESSAGE_MAX}자 이하" }
                call.idempotent(deps) { caller ->
                    val me = myDevice(caller)
                    if (me[Devices.role] != DeviceRole.GUARDIAN.db) throw forbidden("보호자 기기만 응답함")
                    // 보호자들의 응답이 동시에 와도 하나씩 판단하도록 요청 행을 잠근다
                    val row = alertRow(alertId, lock = true) ?: throw alertNotFound()
                    if (!canSee(me, row)) throw forbidden("이 요청을 받은 보호자가 아님")
                    val myId = me[Devices.deviceId]
                    row.decisionOf()?.let { existing ->
                        // 같은 보호자가 같은 응답을 다시 보냄 (응답 유실 뒤 새 키로 재전송) → 그대로 200, 푸시 없음
                        if (existing.decidedBy.deviceId == myId && existing.decision == body.decision) {
                            return@idempotent reply(HttpStatusCode.OK, alertViews(listOf(row)).single(), alertId = alertId)
                        }
                        throw ApiProblem(
                            HttpStatusCode.Conflict, "already-decided", "이미 다른 응답이 있음",
                            "${existing.decidedBy.displayName}님이 먼저 답했어요",
                            extra = buildJsonObject { put("existingDecision", ApiJson.encodeToJsonElement(Decision.serializer(), existing)) },
                        )
                    }
                    if (row[Alerts.status] != AlertStatus.OPEN.db) {
                        throw ApiProblem(HttpStatusCode.Conflict, "alert-closed", "이미 끝난 요청", "상태: ${row[Alerts.status]}")
                    }
                    check400(!(row[Alerts.kind] == AlertKind.NOTICE.db && body.decision == DecisionType.ALLOW)) { "알림 전용 요청은 허용 응답 불가 (이미 허용한 앱)" }
                    val now = deps.now()
                    Alerts.update({ Alerts.alertId eq alertId }) {
                        it[status] = AlertStatus.DECIDED.db
                        it[decision] = body.decision.db
                        it[messageToSenior] = message
                        it[decidedById] = myId
                        it[decidedByName] = me[Devices.displayName]
                        it[decidedAt] = now.utc()
                        it[updatedAt] = now.utc()
                        it[remindAt] = null
                    }
                    val view = alertView(alertId)
                    val senior = PushType.DECISION_MADE.to(
                        fcmToken(row[Alerts.protectedDeviceId]),
                        "alertId" to alertId,
                        "decision" to body.decision.db,
                        "guardianName" to me[Devices.displayName],
                        "packageName" to view.app.packageName,
                    )
                    val others = activeRecipients(alertId).filter { it.second != myId }
                        .map { (_, g) -> PushType.ALERT_UPDATED.to(fcmToken(g), "alertId" to alertId) }
                    reply(HttpStatusCode.OK, view, listOf(senior) + others, alertId)
                }
            }

            // (시니어) 처리 결과. open은 시니어가 먼저 지운 경우(already_removed)만, decided는 모든 결과
            post("/outcome") {
                val alertId = call.parameters["alertId"]!!
                val body = call.receive<OutcomeCreate>()
                call.idempotent(deps) { caller ->
                    val me = myDevice(caller)
                    val row = alertRow(alertId, lock = true) ?: throw alertNotFound()
                    if (row[Alerts.protectedDeviceId] != me[Devices.deviceId]) throw forbidden("이 요청의 시니어 기기만 결과를 보고함")
                    val closed = ApiProblem(HttpStatusCode.Conflict, "alert-closed", "이미 끝난 요청", "상태: ${row[Alerts.status]}")
                    when (AlertStatus.of(row[Alerts.status])!!) {
                        AlertStatus.RESOLVED -> {
                            // 같은 결과 재전송 → 200, 푸시 없음
                            if (row[Alerts.outcome] == body.outcome.db) return@idempotent reply(HttpStatusCode.OK, alertViews(listOf(row)).single(), alertId = alertId)
                            throw closed
                        }
                        AlertStatus.EXPIRED -> throw closed
                        AlertStatus.OPEN -> if (body.outcome != OutcomeType.ALREADY_REMOVED) {
                            throw ApiProblem(HttpStatusCode.Conflict, "not-decided", "보호자 응답 전", "응답 전에는 already_removed만 보고함")
                        }
                        AlertStatus.DECIDED -> {
                            // 보호자 응답과 맞는 결과만: 허용 → allowed(또는 그사이 직접 지움), 삭제 권유 → 지움·취소·이미 지움·정지
                            val decided = DecisionType.of(row[Alerts.decision]!!)
                            val fits = when (decided) {
                                DecisionType.ALLOW -> body.outcome in setOf(OutcomeType.ALLOWED, OutcomeType.ALREADY_REMOVED)
                                DecisionType.RECOMMEND_UNINSTALL -> body.outcome != OutcomeType.ALLOWED
                            }
                            if (!fits) {
                                throw ApiProblem(HttpStatusCode.Conflict, "outcome-mismatch", "보호자 응답과 맞지 않는 결과", "응답: ${decided.db}, 결과: ${body.outcome.db}")
                            }
                        }
                    }
                    val now = deps.now()
                    Alerts.update({ Alerts.alertId eq alertId }) {
                        it[status] = AlertStatus.RESOLVED.db
                        it[outcome] = body.outcome.db
                        // 기기 시계가 앞서 있어도 결과 시각이 미래가 되지 않게
                        it[outcomeAt] = minOf(body.occurredAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS), now).utc()
                        it[updatedAt] = now.utc()
                        it[remindAt] = null
                    }
                    val view = alertView(alertId)
                    val pushes = activeRecipients(alertId).map { (_, g) ->
                        PushType.ALERT_RESOLVED.to(
                            fcmToken(g),
                            "alertId" to alertId,
                            "outcome" to body.outcome.db,
                            "protectedName" to me[Devices.displayName],
                            "appLabel" to view.app.appLabel,
                        )
                    }
                    reply(HttpStatusCode.OK, view, pushes, alertId)
                }
            }
        }
    }
}
