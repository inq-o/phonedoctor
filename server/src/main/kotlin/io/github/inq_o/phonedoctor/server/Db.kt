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
