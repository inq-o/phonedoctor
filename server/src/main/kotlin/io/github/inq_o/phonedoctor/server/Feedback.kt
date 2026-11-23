package io.github.inq_o.phonedoctor.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentLength
import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.ContentType
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.SerializationException
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.upsert
import java.nio.charset.CharacterCodingException
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** 오탐 익명 신고 집계 (V6__false_positive.sql) */
object FalsePositives : Table("false_positive") {
    val alertSource = text("source")
    val packageName = text("package_name")
    val versionCode = long("version_code")
    val signingCertSha256 = text("signing_cert_sha256")
    val judgeVersion = text("judge_version")
    val reasonCodes = text("reason_codes")
    val reports = integer("reports")
    val firstDay = date("first_day")
    val lastDay = date("last_day")
    override val primaryKey = PrimaryKey(alertSource, packageName, versionCode, signingCertSha256, judgeVersion, reasonCodes)
}

@Serializable
data class FalsePositiveReport(
    val source: AlertSource,
    val packageName: String,
    val versionCode: Long,
    val signingCertSha256: String,
    val modelVersion: String? = null,
    val score: Double? = null,
    val reasonCodes: List<ReasonCode>? = null,
    val detectorVersion: String? = null,
)

/** 익명 신고 경로: Firebase 로그인 없이 App Check만 본다 (Auth.kt) */
const val FALSE_POSITIVE_PATH = "/v1/feedback/false-positives"

/** 집계는 마지막 신고 뒤 1년이 지나면 지운다 (Jobs.kt) */
const val FALSE_POSITIVE_KEEP_DAYS = 365L

/** 본문 상한. 정상 신고는 1KB 안쪽 */
private const val BODY_MAX = 4_096L

/**
 * 같은 곳에서 오는 신고 횟수 상한 (시간당). App Check 토큰을 빼내 같은 앱의 오탐을 부풀리는 것을 늦춘다.
 * 주소는 이 서버 메모리에만 두고 저장하지 않는다 (인스턴스가 다시 뜨면 비워짐). 집계 수치는 참고용이지 자동 허용 근거가 아니다
 */
private const val PER_SOURCE_PER_HOUR = 30
private const val SOURCES_MAX = 10_000

/** 주소별 최근 신고 시각. 한 자물쇠로 묶어 표 크기 판단과 기록이 어긋나지 않게 */
private val recent = HashMap<String, ArrayDeque<Instant>>()
private var prunedAt: Instant = Instant.EPOCH

/**
 * 보낸 곳 주소. 앞단 프록시는 실제 접속 주소를 X-Forwarded-For **오른쪽 끝**에 덧붙인다 (Cloud Run 직접이면 맨 끝,
 * 외부 부하 분산기 뒤면 그 앞 = [hops] 2). 더 왼쪽 값은 보낸 쪽이 마음대로 넣을 수 있어 쓰지 않는다.
 * IPv6는 /64로 묶는다 (한 회선이 주소를 바꿔 가며 제한을 피하지 못하게)
 */
internal fun sourceOf(forwardedFor: String?, remoteHost: String, hops: Int = 1): String {
    val parts = forwardedFor?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
    return parts.getOrNull(parts.size - hops)?.let(::normalizeIp) ?: normalizeIp(remoteHost) ?: remoteHost
}

private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
private val IPV6_CHARS = Regex("^[0-9a-fA-F:.]+$")

/**
 * IP 문자열을 같은 회선이면 같은 값이 되게: 포트·대괄호·zone은 떼고, IPv4는 숫자로 다시 쓰고, IPv4를 담은 IPv6는 IPv4로,
 * 나머지 IPv6는 /64로 묶는다. IP가 아니면 null (이름 조회는 하지 않음)
 */
internal fun normalizeIp(raw: String): String? {
    var s = raw.trim()
    if (s.startsWith("[")) s = s.substring(1).substringBefore(']')
    else if (s.count { it == ':' } == 1) s = s.substringBefore(':') // IPv4:포트
    s = s.substringBefore('%')
    IPV4.matchEntire(s)?.let { m ->
        val n = m.groupValues.drop(1).map { it.toInt() }
        return if (n.all { it in 0..255 }) n.joinToString(".") else null
    }
    if (':' !in s || !IPV6_CHARS.matches(s)) return null
    // 문자·콜론·점만 있는 IPv6 꼴이라 이름 조회가 일어나지 않는다
    val addr = runCatching { java.net.InetAddress.getByName(s) }.getOrNull() ?: return null
    // ::ffff:a.b.c.d (IPv4를 담은 IPv6)는 JDK가 Inet4Address로 돌려준다
    if (addr is java.net.Inet4Address) return addr.hostAddress
    val b = addr.address
    if (b.size != 16) return null
    return (0 until 8).joinToString(":") { "%02x".format(b[it]) } + "::/64"
}

/** 시험마다 비운다 */
internal fun clearFeedbackLimits() = synchronized(recent) {
    recent.clear()
    prunedAt = Instant.EPOCH
}

/**
 * 시간당 [PER_SOURCE_PER_HOUR]건. 표가 [SOURCES_MAX]개로 차면 조용해진 주소를 비우고(1분에 한 번만, 요청마다 다 훑지 않게),
 * 그래도 가득하면 **새 주소만** 받지 않는다 (이미 있는 주소의 기록은 건드리지 않음)
 */
internal fun allow(source: String, now: Instant): Boolean = synchronized(recent) {
    val since = now.minus(Duration.ofHours(1))
    var list = recent[source]
    if (list == null) {
        if (recent.size >= SOURCES_MAX && now.isAfter(prunedAt.plusSeconds(60))) {
            prunedAt = now
            recent.values.removeIf { q -> q.all { it <= since } }
        }
        if (recent.size >= SOURCES_MAX) return@synchronized false
        list = ArrayDeque<Instant>().also { recent[source] = it }
    }
    while (list.isNotEmpty() && list.first() <= since) list.removeFirst()
    if (list.size >= PER_SOURCE_PER_HOUR) return@synchronized false
    list.addLast(now)
    true
}

private val CERT = Regex("^[0-9a-f]{64}$")

/** 패키지 이름 (Android 규칙: 영문·숫자·밑줄 조각을 점으로 이음) */
private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

/** 판정기 판 (예: usage-fx0-r7, 2026.11.1). 집계 키라 길이·문자를 묶어 둔다 (쓰레기 값으로 표가 끝없이 늘지 않게) */
private val JUDGE_VERSION = Regex("^[A-Za-z0-9._-]{1,64}$")

private fun FalsePositiveReport.validate() {
    check400(packageName.length <= 255 && PACKAGE.matches(packageName)) { "packageName 형식이 아님" }
    check400(versionCode >= 0) { "versionCode는 0 이상" }
    check400(CERT.matches(signingCertSha256)) { "signingCertSha256은 소문자 16진 64자" }
    when (source) {
        AlertSource.INSTALL_SCAN -> {
            check400(modelVersion != null && JUDGE_VERSION.matches(modelVersion)) { "install_scan은 modelVersion 필수 (64자 이하 영문·숫자·._-)" }
            check400(score != null && score in 0.0..1.0) { "install_scan은 score 필수 (0~1)" }
            check400(reasonCodes != null && reasonCodes.size <= 3) { "install_scan은 reasonCodes 필수 (3개 이하)" }
            check400(detectorVersion == null) { "install_scan에는 detectorVersion을 보내지 않음" }
        }
        AlertSource.SYMPTOM -> {
            check400(detectorVersion != null && JUDGE_VERSION.matches(detectorVersion)) { "symptom은 detectorVersion 필수 (64자 이하 영문·숫자·._-)" }
            check400(modelVersion == null && score == null && reasonCodes == null) { "symptom에는 모델 판정 값을 보내지 않음" }
        }
    }
}

/**
 * 보호자가 [괜찮은 앱이에요]라고 답한 앱의 오탐 신고 (US7). 기기를 묶을 수 있어 Idempotency-Key를 받지 않고,
 * 재전송으로 생긴 중복은 집계 오차로 받아들인다 (openapi). 같은 앱·판·판정기면 횟수만 올린다
 */
fun Route.feedbackRoutes(deps: Deps) {
    post("/feedback/false-positives") {
        val tooLarge = ApiProblem(HttpStatusCode.PayloadTooLarge, "too-large", "본문이 너무 큼")
        if ((call.request.contentLength() ?: 0L) > BODY_MAX) throw tooLarge
        val source = sourceOf(call.request.headers["X-Forwarded-For"], call.request.origin.remoteHost, deps.proxyHops)
        if (!allow(source, deps.now())) throw ApiProblem(HttpStatusCode.TooManyRequests, "too-many-reports", "신고가 너무 잦음", headers = mapOf("Retry-After" to "3600"))
        // call.receive와 같게: 형식이 깨진 Content-Type은 400, JSON(…+json 포함)이 아니면 415. 본문은 UTF-8만 (RFC 8259)
        val type = try {
            call.request.contentType()
        } catch (e: BadContentTypeFormatException) {
            throw badRequest("Content-Type 형식 오류")
        }
        val json = type.match(ContentType.Application.Json) ||
            (type.contentType == "application" && type.contentSubtype.endsWith("+json"))
        if (!json) throw unsupportedMedia()
        val charset = type.parameter("charset")
        if (charset != null && !charset.equals("utf-8", ignoreCase = true) && !charset.equals("utf8", ignoreCase = true)) throw unsupportedMedia()
        // 길이를 밝히지 않은(chunked) 본문도 상한까지만 읽는다
        val bytes = call.receiveChannel().readRemaining(BODY_MAX + 1).readByteArray()
        if (bytes.size > BODY_MAX) throw tooLarge
        val body = try {
            ApiJson.decodeFromString<FalsePositiveReport>(bytes.decodeToString(throwOnInvalidSequence = true))
        } catch (e: CharacterCodingException) {
            throw badRequest("UTF-8이 아닌 바이트")
        } catch (e: SerializationException) {
            throw badRequest(e.message ?: "JSON 형식 오류")
        } catch (e: IllegalArgumentException) {
            throw badRequest(e.message ?: "JSON 형식 오류")
        }
        body.validate()
        val today = deps.now().atOffset(ZoneOffset.UTC).toLocalDate()
        val judge = (if (body.source == AlertSource.INSTALL_SCAN) body.modelVersion else body.detectorVersion)!!.trim()
        val reasons = body.reasonCodes.orEmpty().map { it.name }.distinct().sorted().joinToString(",")
        withContext(Dispatchers.IO) {
            transaction(deps.db) {
                FalsePositives.upsert(
                    onUpdate = {
                        it[FalsePositives.reports] = FalsePositives.reports + 1
                        it[FalsePositives.lastDay] = today
                    },
                ) {
                    it[alertSource] = body.source.db
                    it[packageName] = body.packageName
                    it[versionCode] = body.versionCode
                    it[signingCertSha256] = body.signingCertSha256
                    it[judgeVersion] = judge
                    it[reasonCodes] = reasons
                    it[reports] = 1
                    it[firstDay] = today
                    it[lastDay] = today
                }
            }
        }
        call.respond(HttpStatusCode.Accepted)
    }
}
