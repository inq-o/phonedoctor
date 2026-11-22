package io.github.inq_o.phonedoctor.core.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import retrofit2.HttpException

/**
 * 서버가 돌려준 RFC 9457 오류. [slug]는 `type` URI 끝부분(예: `pairing-code-expired`)이고 화면은 이 값으로 경우를 가른다.
 * [body]에는 `existingDecision` 같은 추가 필드도 들어 있다
 */
class ApiException(
    val status: Int,
    val slug: String?,
    val title: String?,
    val detail: String?,
    val body: JsonObject?,
    /** 429의 Retry-After(초) */
    val retryAfterSeconds: Long?,
    cause: Throwable? = null,
) : RuntimeException("HTTP $status ${slug ?: ""} ${detail ?: ""}".trim(), cause) {
    companion object {
        private const val PROBLEM_BASE = "https://phonedoctor.example/problems/"

        internal fun from(e: HttpException, json: Json): ApiException {
            val response = e.response()
            val text = runCatching { response?.errorBody()?.string() }.getOrNull()
            val body = text?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }
            fun field(name: String) = body?.get(name)?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            return ApiException(
                status = e.code(),
                slug = field("type")?.removePrefix(PROBLEM_BASE),
                title = field("title"),
                detail = field("detail"),
                body = body,
                retryAfterSeconds = response?.headers()?.get("Retry-After")?.toLongOrNull(),
                cause = e,
            )
        }
    }
}
