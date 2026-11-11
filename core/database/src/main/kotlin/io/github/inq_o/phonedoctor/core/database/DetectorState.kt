package io.github.inq_o.phonedoctor.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/** 판정기 상태(커서·관측 구간·직전 화면 등) 한 줄. 판정 결과와 같은 트랜잭션으로 저장한다 */
@Entity(tableName = "detector_state")
data class DetectorStateEntity(
    @PrimaryKey val id: Int = 1,
    val schema: String,
    val json: String,
    val updatedAt: Long,
)

@Dao
interface DetectorStateDao {
    @Query("SELECT * FROM detector_state WHERE id = 1")
    suspend fun get(): DetectorStateEntity?

    @Upsert
    suspend fun put(state: DetectorStateEntity)
}
