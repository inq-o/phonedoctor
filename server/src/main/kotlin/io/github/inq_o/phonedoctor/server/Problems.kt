package io.github.inq_o.phonedoctor.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException

private const val PROBLEM_BASE = "https://phonedoctor.example/problems/"
private val ProblemJson = ContentType("application", "problem+json")

/**
 * RFC 9457 오류 응답. [slug]는 `type` URI 끝부분이고, 앱은 이 값으로 경우를 가른다.
 * [extra]는 본문에 함께 넣는 필드 (예: 이미 결정된 요청의 `existingDecision`)
 */
class ApiProblem(
    val status: HttpStatusCode,
    val slug: String,
    val title: String,
    val detail: String? = null,
    val extra: JsonObject? = null,
) : RuntimeException(title)

fun badRequest(detail: String) = ApiProblem(HttpStatusCode.BadRequest, "bad-request", "요청 형식 오류", detail)
fun forbidden(detail: String) = ApiProblem(HttpStatusCode.Forbidden, "forbidden", "권한 없음", detail)
fun notFound(detail: String) = ApiProblem(HttpStatusCode.NotFound, "not-found", "없음", detail)

/** 조건이 거짓이면 400 */
fun check400(condition: Boolean, detail: () -> String) {
    if (!condition) throw badRequest(detail())
}

fun Application.installProblems() {
    install(StatusPages) {
        exception<ApiProblem> { call, p -> call.respondProblem(p) }
        exception<BadRequestException> { call, e ->
            call.respondProblem(badRequest(e.cause?.message ?: e.message ?: "본문을 읽을 수 없음"))
        }
        exception<ExposedSQLException> { call, e ->
            // 동시에 같은 개체를 만들 때(유일 제약 위반). 다시 보내면 기존 값을 받는다
            if (e.sqlState == "23505") {
                call.respondProblem(ApiProblem(HttpStatusCode.Conflict, "conflict", "동시 요청 충돌", "잠시 뒤 다시 보내세요"))
            } else {
                call.application.log.error("DB 오류", e)
                call.respondProblem(ApiProblem(HttpStatusCode.InternalServerError, "internal", "서버 오류"))
            }
        }
        exception<Throwable> { call, e ->
            call.application.log.error("처리 못 한 오류", e)
            call.respondProblem(ApiProblem(HttpStatusCode.InternalServerError, "internal", "서버 오류"))
        }
    }
}

suspend fun io.ktor.server.application.ApplicationCall.respondProblem(p: ApiProblem) {
    val body = buildJsonObject {
        put("type", JsonPrimitive(PROBLEM_BASE + p.slug))
        put("title", JsonPrimitive(p.title))
        put("status", JsonPrimitive(p.status.value))
        p.detail?.let { put("detail", JsonPrimitive(it)) }
        p.extra?.forEach { (k, v) -> put(k, v) }
    }
    respondText(ApiJson.encodeToString(JsonObject.serializer(), body), ProblemJson, p.status)
}
