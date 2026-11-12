package io.github.inq_o.phonedoctor.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * 광고로 센 화면 전환 1건 (FR-009·010). 기기 밖으로 보내지 않는다.
 * 서명·버전은 판정할 때 설치돼 있던 판의 값이다. 정상으로 본 전환은 저장하지 않는다.
 */
@Entity(
    tableName = "symptom_event",
    indices = [Index("packageName", "signingCertSha256", "versionCode", "localDate"), Index("resumedAt")],
)
data class SymptomEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val signingCertSha256: String,
    val versionCode: Long,
    val className: String,
    val resumedAt: Long,
    /** 기록할 때의 기기 시간대 기준 날짜 (yyyy-MM-dd) */
    val localDate: String,
    val segmentId: Int,
    val detectorVersion: String,
)

data class DailySymptomCount(
    val packageName: String,
    val signingCertSha256: String,
    val versionCode: Long,
    val localDate: String,
    @ColumnInfo(name = "n") val count: Int,
    val firstAt: Long,
    val lastAt: Long,
)

@Dao
interface SymptomEventDao {
    @Insert
    suspend fun insert(events: List<SymptomEventEntity>)

    @Query(
        "SELECT packageName, signingCertSha256, versionCode, localDate, COUNT(*) AS n, " +
            "MIN(resumedAt) AS firstAt, MAX(resumedAt) AS lastAt FROM symptom_event " +
            "WHERE packageName = :packageName AND signingCertSha256 = :cert AND versionCode = :versionCode " +
            "AND localDate >= :sinceDate GROUP BY localDate ORDER BY localDate",
    )
    suspend fun dailyCounts(packageName: String, cert: String, versionCode: Long, sinceDate: String): List<DailySymptomCount>

    @Query(
        "SELECT packageName, signingCertSha256, versionCode, localDate, COUNT(*) AS n, " +
            "MIN(resumedAt) AS firstAt, MAX(resumedAt) AS lastAt FROM symptom_event " +
            "WHERE localDate >= :sinceDate GROUP BY packageName, signingCertSha256, versionCode, localDate " +
            "ORDER BY localDate DESC, n DESC",
    )
    fun observeDailyCounts(sinceDate: String): Flow<List<DailySymptomCount>>

    @Query("DELETE FROM symptom_event WHERE resumedAt < :before")
    suspend fun deleteOlderThan(before: Long)
}
