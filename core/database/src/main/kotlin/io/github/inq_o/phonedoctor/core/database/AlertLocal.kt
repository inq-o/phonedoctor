package io.github.inq_o.phonedoctor.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 확인이 필요한 앱 1개 (data-model.md `alert_local`). (packageName, signingCertSha256, versionCode)당 한 줄.
 * 가족과 연결한 시니어 폰은 서버 요청의 사본(서버 ID·보낸 근거·보호자 응답)도 여기에 둔다 (판 2).
 */
@Entity(
    tableName = "alert_local",
    indices = [Index("packageName", "signingCertSha256", "versionCode", unique = true), Index("status")],
)
data class AlertLocalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val signingCertSha256: String,
    val versionCode: Long,
    val label: String,
    val source: String,
    val status: String,
    val outcome: String?,
    val date: String,
    val count: Int,
    val days: Int,
    val firstAt: Long,
    val lastAt: Long,
    val detectorVersion: String,
    /** 이 요청이 (다시) 열린 날짜. days는 이 날부터 센다 */
    val openedDate: String,
    val createdAt: Long,
    val updatedAt: Long,
    /** approval(승인 요청) / notice(보호자가 허용한 앱의 알림 전용, FR-007) */
    @ColumnInfo(defaultValue = "approval") val kind: String = "approval",
    /** 서버 요청 ID. 아직 보내지 않았거나 받을 보호자가 없었으면 null */
    @ColumnInfo(defaultValue = "NULL") val serverAlertId: String? = null,
    /** 서버가 받은 마지막 근거 (date, count). 근거가 바뀌면 다시 보낸다 */
    @ColumnInfo(defaultValue = "NULL") val sentDate: String? = null,
    @ColumnInfo(defaultValue = "NULL") val sentCount: Int? = null,
    /** 서버 요청 상태 open·decided·resolved·expired (마지막으로 안 값) */
    @ColumnInfo(defaultValue = "NULL") val serverStatus: String? = null,
    /** 보호자 응답 recommend_uninstall·allow */
    @ColumnInfo(defaultValue = "NULL") val decision: String? = null,
    @ColumnInfo(defaultValue = "NULL") val guardianName: String? = null,
    @ColumnInfo(defaultValue = "NULL") val messageToSenior: String? = null,
    /** 처리 결과를 서버에 보냈거나 보낼 수 없음이 정해짐 */
    @ColumnInfo(defaultValue = "0") val outcomeSent: Boolean = false,
    /** 서버에 보낼 것(새 근거·처리 결과)이 있음. 가족 연결 동기화 작업이 비운다 */
    @ColumnInfo(defaultValue = "0") val needsSync: Boolean = false,
)

@Dao
interface AlertLocalDao {
    @Insert
    suspend fun insert(alert: AlertLocalEntity): Long

    @Update
    suspend fun update(alert: AlertLocalEntity)

    @Query("SELECT * FROM alert_local WHERE packageName = :packageName AND signingCertSha256 = :cert AND versionCode = :versionCode")
    suspend fun find(packageName: String, cert: String, versionCode: Long): AlertLocalEntity?

    @Query("SELECT * FROM alert_local WHERE id = :id")
    suspend fun get(id: Long): AlertLocalEntity?

    @Query("SELECT * FROM alert_local WHERE id = :id")
    fun observe(id: Long): Flow<AlertLocalEntity?>

    @Query("SELECT * FROM alert_local WHERE status = 'OPEN' ORDER BY lastAt DESC")
    fun observeOpen(): Flow<List<AlertLocalEntity>>

    @Query("SELECT * FROM alert_local WHERE status = 'OPEN'")
    suspend fun open(): List<AlertLocalEntity>

    @Query("SELECT * FROM alert_local")
    suspend fun all(): List<AlertLocalEntity>

    @Query("SELECT * FROM alert_local WHERE needsSync = 1 ORDER BY id")
    suspend fun pendingSync(): List<AlertLocalEntity>

    @Query("SELECT * FROM alert_local WHERE serverAlertId = :serverAlertId")
    suspend fun findByServerId(serverAlertId: String): AlertLocalEntity?

    // 동기화 결과는 열 단위로 쓴다: 그사이 검사·푸시가 같은 줄의 다른 열을 바꿨을 수 있어 줄 통째로 덮어쓰지 않는다

    @Query("UPDATE alert_local SET serverAlertId = :serverAlertId, sentDate = :date, sentCount = :count, serverStatus = :serverStatus WHERE id = :id")
    suspend fun markSent(id: Long, serverAlertId: String, date: String, count: Int, serverStatus: String)

    @Query("UPDATE alert_local SET serverStatus = :serverStatus WHERE id = :id")
    suspend fun setServerStatus(id: Long, serverStatus: String)

    @Query(
        "UPDATE alert_local SET serverStatus = :serverStatus, decision = :decision, guardianName = :guardianName, " +
            "messageToSenior = COALESCE(:messageToSenior, messageToSenior) WHERE id = :id",
    )
    suspend fun setDecision(id: Long, serverStatus: String, decision: String, guardianName: String?, messageToSenior: String?)

    @Query("UPDATE alert_local SET outcomeSent = 1 WHERE id = :id")
    suspend fun markOutcomeSent(id: Long)

    /** 읽은 뒤 바뀌지 않았을 때만 비운다 (보내는 동안 새 근거·결과가 생겼으면 다음에 또 보낸다) */
    @Query("UPDATE alert_local SET needsSync = 0 WHERE id = :id AND updatedAt = :seenUpdatedAt")
    suspend fun clearNeedsSync(id: Long, seenUpdatedAt: Long)

    /** 새 보호자와 연결됨: 열린 요청을 서버에 (다시) 알린다 */
    @Query("UPDATE alert_local SET needsSync = 1 WHERE status = 'OPEN'")
    suspend fun markOpenForSync()

    /** 모든 데이터 지우기 뒤: 서버 요청은 서버에서 지워졌으므로 사본도 비운다 */
    @Query(
        "UPDATE alert_local SET serverAlertId = NULL, sentDate = NULL, sentCount = NULL, serverStatus = NULL, decision = NULL, " +
            "guardianName = NULL, messageToSenior = NULL, outcomeSent = 0, needsSync = 0",
    )
    suspend fun forgetServer()
}

/** 보호자가 [괜찮은 앱이에요]로 허용한 앱 (data-model.md `allow_entry`). 같은 판은 다시 묻지 않는다 */
@Entity(tableName = "allow_entry", primaryKeys = ["packageName", "signingCertSha256", "versionCode"])
data class AllowEntryEntity(
    val packageName: String,
    val signingCertSha256: String,
    val versionCode: Long,
    val allowedBy: String,
    val createdAt: Long,
)

@Dao
interface AllowEntryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: AllowEntryEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM allow_entry WHERE packageName = :packageName AND signingCertSha256 = :cert AND versionCode = :versionCode)")
    suspend fun isAllowed(packageName: String, cert: String, versionCode: Long): Boolean
}
