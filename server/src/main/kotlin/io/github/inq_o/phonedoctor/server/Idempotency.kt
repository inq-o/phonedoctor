package io.github.inq_o.phonedoctor.server

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Duration
import java.util.UUID

/**
 * 응답 상태와 JSON 본문. 같은 Idempotency-Key 재전송에 그대로 돌려주려고 문자열로 들고 있음.
 * [pushes]는 커밋 뒤 처음 한 번만 보낸다 (재전송 응답에는 없음)
 */
class Reply(val status: HttpStatusCode, val body: String, val pushes: List<Push> = emptyList(), val alertId: String? = null)

/**
 * [alertId]: 응답 본문에 담긴 요청. 저장한 응답을 그 요청에 묶어, 요청이 지워지면(연결 해제·기기 삭제·보관 기간) 저장 응답도
 * 함께 지워진다(외래 키 연쇄). 지워진 요청 내용이 재전송 응답으로 다시 나가지 않게 (NFR-09)
 */
inline fun <reified T> reply(status: HttpStatusCode, value: T, pushes: List<Push> = emptyList(), alertId: String? = null) =
    Reply(status, ApiJson.encodeToString(value), pushes, alertId)

private val KEEP = Duration.ofHours(24)

/**
 * POST 공통 규칙: `Idempotency-Key`(UUID) 필수, 같은 키로 다시 오면 첫 성공 응답을 그대로 돌려준다.
 * 처리와 키 저장을 한 트랜잭션에서 해, 동시에 같은 키가 두 번 와도 한쪽만 반영된다.
 * 실패 응답(4xx)은 저장하지 않는다: 상태가 바뀐 뒤(예: 연결 확정 뒤) 같은 키로 다시 보내면 새로 판단한다
 */
suspend fun ApplicationCall.idempotent(deps: Deps, block: JdbcTransaction.(Caller) -> Reply) {
    val caller = caller
    val raw = request.headers["Idempotency-Key"]
    val key = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }?.toString()
        ?: throw badRequest("Idempotency-Key 헤더(UUID)가 필요함")
    val method = request.httpMethod.value
    val path = request.path()
    val (reply, replayed) = withContext(Dispatchers.IO) {
        stored(deps, caller.uid, key, method, path)?.let { return@withContext it to true }
        try {
            transaction(deps.db) {
                val r = block(caller)
                if (r.status.isSuccess()) {
                    IdempotencyKeys.insert {
                        it[firebaseUid] = caller.uid
                        it[IdempotencyKeys.key] = key
                        it[IdempotencyKeys.method] = method
                        it[IdempotencyKeys.path] = path
                        it[status] = r.status.value
                        it[body] = r.body
                        it[IdempotencyKeys.alertId] = r.alertId
                        it[createdAt] = deps.now().utc()
                    }
                }
                r
            } to false
        } catch (e: ExposedSQLException) {
            // 같은 키가 동시에 와서 다른 쪽이 먼저 저장함 → 그쪽 응답을 돌려준다
            if (e.sqlState != "23505") throw e
            (stored(deps, caller.uid, key, method, path) ?: throw e) to true
        }
    }
    if (!replayed) {
        withContext(Dispatchers.IO) {
            deps.sendAll(reply.pushes)
            sweepExpired(deps)
        }
    }
    if (replayed) response.header("Idempotent-Replayed", "true")
    respondText(reply.body, ContentType.Application.Json, reply.status)
}

private fun stored(deps: Deps, uid: String, key: String, method: String, path: String): Reply? = transaction(deps.db) {
    val where = (IdempotencyKeys.firebaseUid eq uid) and (IdempotencyKeys.key eq key)
    var row = IdempotencyKeys.selectAll().where(where).singleOrNull() ?: return@transaction null
    val created = row[IdempotencyKeys.createdAt]
    if (created.toInstant() < deps.now().minus(KEEP)) {
        // 읽은 그 만료 기록만 지운다. 그사이 같은 키의 다른 요청이 새로 저장했으면 지우지 않고 그 응답을 돌려준다
        if (IdempotencyKeys.deleteWhere { where and (IdempotencyKeys.createdAt eq created) } > 0) return@transaction null
        row = IdempotencyKeys.selectAll().where(where).singleOrNull() ?: return@transaction null
    }
    if (row[IdempotencyKeys.method] != method || row[IdempotencyKeys.path] != path) {
        throw ApiProblem(HttpStatusCode.UnprocessableEntity, "idempotency-key-reused", "다른 요청에 쓴 Idempotency-Key", "요청마다 새 UUID를 쓰세요")
    }
    Reply(HttpStatusCode.fromValue(row[IdempotencyKeys.status]), row[IdempotencyKeys.body])
}

private val SWEEP_EVERY = Duration.ofHours(1)

/**
 * 보관 기간(24시간)이 지난 키를 지운다. 서버 인스턴스마다 1시간에 한 번, 새 POST를 처리한 뒤에.
 * 이미 처리한 POST의 응답에 영향을 주지 않게 실패는 기록만 하고, 다음 POST에서 다시 시도한다
 */
private fun sweepExpired(deps: Deps) {
    val now = deps.now()
    val last = deps.keysSweptAt.get()
    if (now < last.plus(SWEEP_EVERY) || !deps.keysSweptAt.compareAndSet(last, now)) return
    try {
        transaction(deps.db) {
            IdempotencyKeys.deleteWhere { IdempotencyKeys.createdAt less now.minus(KEEP).utc() }
        }
    } catch (e: Exception) {
        deps.keysSweptAt.compareAndSet(now, last)
        log.warn("만료된 Idempotency-Key 정리 실패, 다음 POST에서 다시 시도", e)
    }
}

private val log = org.slf4j.LoggerFactory.getLogger("Idempotency")
