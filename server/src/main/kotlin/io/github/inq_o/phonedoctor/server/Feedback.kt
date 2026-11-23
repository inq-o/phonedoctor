package io.github.inq_o.phonedoctor.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.contentLength
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
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

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
private val recent = ConcurrentHashMap<String, MutableList<Instant>>()
private const val SOURCES_MAX = 10_000

/**
 * 보낸 곳 주소. Cloud Run 앞단(Google Front End)은 실제 접속 주소를 X-Forwarded-For **맨 뒤**에 덧붙인다.
 * 앞쪽 값은 보낸 쪽이 마음대로 넣을 수 있어 쓰지 않는다 (매번 바꿔 제한을 피하지 못하게)
 */
internal fun sourceOf(forwardedFor: String?, remoteHost: String): String =
    forwardedFor?.substringAfterLast(',')?.trim()?.ifEmpty { null } ?: remoteHost

/** 시험마다 비운다 */
internal fun clearFeedbackLimits() = recent.clear()

internal fun allow(source: String, now: Instant): Boolean {
    val since = now.minus(Duration.ofHours(1))
    var ok = false
    recent.compute(source) { _, list ->
        val kept = (list ?: mutableListOf()).filterTo(mutableListOf()) { it > since }
        ok = kept.size < PER_SOURCE_PER_HOUR
        if (ok) kept += now
        kept.ifEmpty { null }
    }
    // 오래 조용한 주소는 버린다 (메모리가 늘지 않게). 그래도 가득하면 새 주소는 받지 않는다 (한 시간 뒤 비워짐)
    if (recent.size > SOURCES_MAX) {
        recent.entries.removeIf { e -> e.value.all { it <= since } }
        if (recent.size > SOURCES_MAX) {
            recent.remove(source)
            return false
        }
    }
    return ok
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
        val source = sourceOf(call.request.headers["X-Forwarded-For"], call.request.origin.remoteHost)
        if (!allow(source, deps.now())) throw ApiProblem(HttpStatusCode.TooManyRequests, "too-many-reports", "신고가 너무 잦음", headers = mapOf("Retry-After" to "3600"))
        if (call.request.contentType().withoutParameters() != ContentType.Application.Json) throw unsupportedMedia()
        // 길이를 밝히지 않은(chunked) 본문도 상한까지만 읽는다
        val bytes = call.receiveChannel().readRemaining(BODY_MAX + 1).readByteArray()
        if (bytes.size > BODY_MAX) throw tooLarge
        val body = try {
            ApiJson.decodeFromString<FalsePositiveReport>(bytes.decodeToString())
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
