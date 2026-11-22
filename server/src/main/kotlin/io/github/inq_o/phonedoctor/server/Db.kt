package io.github.inq_o.phonedoctor.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.DatabaseConfig
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.json.jsonb
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource

// 표 정의는 src/main/resources/db/migration (Flyway)이 원본이다. 여기는 그 열을 코드에서 쓰기 위한 사상

object Devices : Table("device") {
    val deviceId = text("device_id")
    val firebaseUid = text("firebase_uid")
    val role = text("role")
    val displayName = text("display_name")
    val fcmToken = text("fcm_token")
    val appVersionCode = integer("app_version_code")
    val sdkInt = integer("sdk_int")
    val manufacturer = text("manufacturer").nullable()
    val locale = text("locale").nullable()
    val notifyCaution = bool("notify_caution")
    val createdAt = timestampWithTimeZone("created_at")
    val lastSeenAt = timestampWithTimeZone("last_seen_at").nullable()
    val lastCheckedAt = timestampWithTimeZone("last_checked_at").nullable()
    val checkedReportedAt = timestampWithTimeZone("checked_reported_at").nullable()
    val protection = jsonb("protection", ApiJson, ProtectionState.serializer()).nullable()
    override val primaryKey = PrimaryKey(deviceId)
}

object IdempotencyKeys : Table("idempotency_key") {
    val firebaseUid = text("firebase_uid")
    val key = text("key")
    val method = text("method")
    val path = text("path")
    val status = integer("status")
    val body = text("body")
    val createdAt = timestampWithTimeZone("created_at")
    override val primaryKey = PrimaryKey(firebaseUid, key)
}

object PairingCodes : Table("pairing_code") {
    val code = text("code")
    val protectedDeviceId = text("protected_device_id")
    val expiresAt = timestampWithTimeZone("expires_at")
    val used = bool("used")
    override val primaryKey = PrimaryKey(code)
}

object Links : Table("link") {
    val linkId = text("link_id")
    val protectedDeviceId = text("protected_device_id")
    val guardianDeviceId = text("guardian_device_id")
    val status = text("status")
    val createdAt = timestampWithTimeZone("created_at")
    val activatedAt = timestampWithTimeZone("activated_at").nullable()
    val revokedAt = timestampWithTimeZone("revoked_at").nullable()
    val contactLostAt = timestampWithTimeZone("contact_lost_at").nullable()
    override val primaryKey = PrimaryKey(linkId)
}

object PairingAttempts : Table("pairing_attempt") {
    val firebaseUid = text("firebase_uid")
    val attemptedAt = timestampWithTimeZone("attempted_at")
}

object Alerts : Table("alert") {
    val alertId = text("alert_id")
    val protectedDeviceId = text("protected_device_id")
    val status = text("status")
    val alertSource = text("source")
    val kind = text("kind")
    val packageName = text("package_name")
    val signingCertSha256 = text("signing_cert_sha256")
    val versionCode = long("version_code")
    val app = jsonb("app", ApiJson, AppInfo.serializer())
    val verdict = jsonb("verdict", ApiJson, Verdict.serializer()).nullable()
    val symptom = jsonb("symptom", ApiJson, SymptomEvidence.serializer()).nullable()
    val detectedAt = timestampWithTimeZone("detected_at")
    val createdAt = timestampWithTimeZone("created_at")
    val updatedAt = timestampWithTimeZone("updated_at")
    val remindAt = timestampWithTimeZone("remind_at").nullable()
    val expiresAt = timestampWithTimeZone("expires_at")
    val deleteAt = timestampWithTimeZone("delete_at")
    val decision = text("decision").nullable()
    val messageToSenior = text("message_to_senior").nullable()
    val decidedById = text("decided_by_id").nullable()
    val decidedByName = text("decided_by_name").nullable()
    val decidedAt = timestampWithTimeZone("decided_at").nullable()
    val outcome = text("outcome").nullable()
    val outcomeAt = timestampWithTimeZone("outcome_at").nullable()
    override val primaryKey = PrimaryKey(alertId)
}

object AlertLinks : Table("alert_link") {
    val alertId = text("alert_id")
    val linkId = text("link_id")
    override val primaryKey = PrimaryKey(alertId, linkId)
}

fun Instant.utc(): OffsetDateTime = atOffset(ZoneOffset.UTC)

/** 마이그레이션을 적용하고 Exposed에 연결한다 */
fun connect(dataSource: DataSource): Database {
    Flyway.configure().dataSource(dataSource).load().migrate()
    // SQL 오류 때 블록을 다시 돌리지 않는다(기본은 재시도). 블록 안 처리가 두 번 일어나지 않게 하고, 충돌은 호출한 쪽이 판단
    return Database.connect(dataSource, databaseConfig = DatabaseConfig { defaultMaxAttempts = 1 })
}

fun dataSource(config: Config): DataSource = HikariDataSource(
    HikariConfig().apply {
        jdbcUrl = config.databaseUrl
        username = config.databaseUser
        password = config.databasePassword
        // Cloud Run 인스턴스 1개당 연결 수. Neon 무료 등급 연결 한도 안에서
        maximumPoolSize = 5
    },
)
