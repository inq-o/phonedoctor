package io.github.inq_o.phonedoctor.server

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertTrue
import kotlin.test.fail

/** 시험 내내 쓰는 Postgres 하나 (실제 Postgres 실행 파일, Docker 불필요) */
object TestDb {
    private val pg: EmbeddedPostgres by lazy { EmbeddedPostgres.start() }
    val db: Database by lazy { connect(pg.postgresDatabase) }

    /** 시험마다 빈 표에서 시작 */
    fun reset() = transaction(db) {
        exec("TRUNCATE device, idempotency_key, pairing_attempt CASCADE")
    }
}

/**
 * 가짜 토큰: ID 토큰 `id:<uid>` (익명) 또는 `id:<uid>:google:<이름>`, App Check 토큰 `ok`.
 */
object FakeVerifier : TokenVerifier {
    override fun verifyIdToken(token: String): Caller? {
        val p = token.split(':')
        if (p.size < 2 || p[0] != "id") return null
        return if (p.getOrNull(2) == "google") Caller(p[1], p.getOrNull(3), "google.com") else Caller(p[1], null, "anonymous")
    }

    override fun verifyAppCheck(token: String) = token == "ok"
}

/** 보낸 푸시를 모아 두는 가짜. [failing]이면 전송 실패를 흉내 낸다 */
object FakePusher : Pusher {
    val sent = java.util.concurrent.CopyOnWriteArrayList<Push>()

    @Volatile var failing = false

    override fun send(push: Push) {
        if (failing) error("FCM 실패 흉내")
        sent += push
    }

    fun types() = sent.map { it.data["type"] }
}

/** 시각을 옮길 수 있는 시계 */
class MovableClock(var instant: Instant = Instant.parse("2026-11-21T03:00:00Z")) : Clock() {
    override fun getZone() = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId?) = this
    override fun instant() = instant
}

fun serverTest(clock: MovableClock = MovableClock(), block: suspend ApplicationTestBuilder.(MovableClock) -> Unit) {
    TestDb.reset()
    FakePusher.sent.clear()
    FakePusher.failing = false
    testApplication {
        application { module(Deps(TestDb.db, FakeVerifier, FakePusher, clock)) }
        block(clock)
    }
}

/** 시니어(익명)·보호자(Google) 요청 */
const val SENIOR = "id:senior1"
const val GUARDIAN = "id:guardian1:google:김보호"

suspend fun HttpClient.api(
    method: HttpMethod,
    path: String,
    token: String? = SENIOR,
    body: String? = null,
    key: String? = if (method == HttpMethod.Post) UUID.randomUUID().toString() else null,
    appCheck: String? = "ok",
    extra: HttpRequestBuilder.() -> Unit = {},
): HttpResponse = request("/v1$path") {
    this.method = method
    token?.let { header("Authorization", "Bearer $it") }
    appCheck?.let { header("X-Firebase-AppCheck", it) }
    key?.let { header("Idempotency-Key", it) }
    body?.let {
        contentType(ContentType.Application.Json)
        setBody(it)
    }
    extra()
}

suspend fun HttpResponse.json(): JsonObject = ApiJson.parseToJsonElement(bodyAsText()).jsonObject

/** 응답 본문이 openapi.yaml의 스키마를 지키는지 대조 (JSON Schema 2020-12, 형식 검사 포함) */
object Contract {
    private val yaml = ObjectMapper(YAMLFactory())
    private val doc = yaml.readTree(File(System.getProperty("openapi.path") ?: fail("openapi.path 시스템 속성 없음")))
    private val factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
    private val config = SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build()

    fun check(schema: String, body: String) {
        val root = yaml.createObjectNode()
        root.put("\$schema", "https://json-schema.org/draft/2020-12/schema")
        root.put("\$ref", "#/components/schemas/$schema")
        root.set<ObjectNode>("components", doc["components"])
        val errors = factory.getSchema(root, config).validate(ObjectMapper().readTree(body))
        assertTrue(errors.isEmpty(), "$schema 계약 위반: $errors\n$body")
    }
}

suspend fun HttpResponse.contract(schema: String): JsonObject {
    val text = bodyAsText()
    Contract.check(schema, text)
    return ApiJson.parseToJsonElement(text).jsonObject
}
