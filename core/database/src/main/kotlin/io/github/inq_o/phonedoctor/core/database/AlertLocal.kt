package io.github.inq_o.phonedoctor.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 확인이 필요한 앱 1개 (data-model.md `alert_local`). (packageName, signingCertSha256, versionCode)당 한 줄.
 * 서버 연결 전이라 서버 요청 ID·보호자 응답 열은 아직 없다.
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
}
