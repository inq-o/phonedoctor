package io.github.inq_o.phonedoctor.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
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
    val reasonCodes: List<String>? = null,
    val detectorVersion: String? = null,
)

/** 익명 신고 경로: Firebase 로그인 없이 App Check만 본다 (Auth.kt) */
const val FALSE_POSITIVE_PATH = "/v1/feedback/false-positives"

private val CERT = Regex("^[0-9a-f]{64}$")

private fun FalsePositiveReport.validate() {
    check400(packageName.isNotBlank() && packageName.length <= 255) { "packageName이 비었거나 김" }
    check400(versionCode >= 0) { "versionCode는 0 이상" }
    check400(CERT.matches(signingCertSha256)) { "signingCertSha256은 소문자 16진 64자" }
    when (source) {
        AlertSource.INSTALL_SCAN -> check400(!modelVersion.isNullOrBlank() && score != null && reasonCodes != null) {
            "install_scan은 modelVersion·score·reasonCodes 필수"
        }
        AlertSource.SYMPTOM -> check400(!detectorVersion.isNullOrBlank()) { "symptom은 detectorVersion 필수" }
    }
}

/**
 * 보호자가 [괜찮은 앱이에요]라고 답한 앱의 오탐 신고 (US7). 기기를 묶을 수 있어 Idempotency-Key를 받지 않고,
 * 재전송으로 생긴 중복은 집계 오차로 받아들인다 (openapi). 같은 앱·판·판정기면 횟수만 올린다
 */
fun Route.feedbackRoutes(deps: Deps) {
    post("/feedback/false-positives") {
        val body = call.receive<FalsePositiveReport>()
        body.validate()
        val today = deps.now().atOffset(ZoneOffset.UTC).toLocalDate()
        val judge = (if (body.source == AlertSource.INSTALL_SCAN) body.modelVersion else body.detectorVersion)!!.trim()
        val reasons = body.reasonCodes.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted().joinToString(",")
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
