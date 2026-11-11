package io.github.inq_o.phonedoctor.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** 검사 1회 기록. 권한 없음·잠금·조회 실패를 "이벤트 0건"과 구분해 남긴다 */
@Entity(tableName = "scan_run", indices = [Index("startedAt")])
data class ScanRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val trigger: String,
    val result: String,
    val queryFrom: Long?,
    val queryTo: Long?,
    val eventCount: Int,
    val transitionCount: Int,
    val adCount: Int,
    val holdCount: Int,
    val storedCount: Int,
    val segmentId: Int,
    val segmentKind: String,
    val quarantined: Boolean,
    val durationMs: Long,
    val note: String,
)

@Dao
interface ScanRunDao {
    @Insert
    suspend fun insert(run: ScanRunEntity)

    @Query("SELECT * FROM scan_run ORDER BY startedAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ScanRunEntity>>

    @Query("SELECT * FROM scan_run WHERE result = :result ORDER BY startedAt DESC, id DESC LIMIT 1")
    fun observeLatest(result: String): Flow<ScanRunEntity?>

    @Query("DELETE FROM scan_run WHERE startedAt < :before")
    suspend fun deleteOlderThan(before: Long)
}
