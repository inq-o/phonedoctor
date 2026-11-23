package io.github.inq_o.phonedoctor.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Chrome 사이트 알림 1건 (US10, FR-050). 알림 내용은 저장하지 않는다: 사이트 주소·채널·시각·광고 문구가 있었는지만.
 * 다른 앱의 알림은 받는 즉시 버려 여기에 오지 않는다
 */
@Entity(tableName = "site_notification", indices = [Index("site", "localDate")])
data class SiteNotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 사이트 주소 (예: https://ads.example.com) */
    val site: String,
    /** 알림을 보낸 브라우저 패키지 */
    val browserPackage: String,
    /** 브라우저가 사이트마다 만든 알림 채널 (`web:https://…;숫자`) */
    val channelId: String,
    val postedAt: Long,
    val localDate: String,
    /** 광고·겁주기 문구가 있었는지 (문구 자체는 남기지 않는다) */
    val adWords: Boolean,
)

/** 사이트별 하루 횟수 */
data class SiteDailyCount(val site: String, val localDate: String, val n: Int, val adWords: Int)

/** 사이트 하나의 상태 (시니어에게 물었는지, 껐는지, 괜찮다고 했는지) */
@Entity(tableName = "site_alert")
data class SiteAlertEntity(
    @PrimaryKey val site: String,
    val browserPackage: String,
    val channelId: String,
    /** ASKED(끌까요? 물음) · MUTED(끄는 화면을 열었음) · ALLOWED(괜찮은 사이트) · STILL_ON(끈 뒤에도 옴) */
    val status: String,
    /** 물었을 때의 근거 날짜와 그날 횟수 */
    val date: String,
    val count: Int,
    val askedAt: Long,
    val mutedAt: Long? = null,
)

@Dao
interface SiteNotificationDao {
    @Insert
    suspend fun insert(row: SiteNotificationEntity)

    @Query(
        "SELECT site, localDate, COUNT(*) AS n, SUM(adWords) AS adWords FROM site_notification " +
            "WHERE site = :site AND localDate = :date GROUP BY site, localDate",
    )
    suspend fun dailyCount(site: String, date: String): SiteDailyCount?

    @Query("SELECT * FROM site_alert WHERE site = :site")
    suspend fun alert(site: String): SiteAlertEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAlert(alert: SiteAlertEntity)

    @Query("SELECT * FROM site_alert ORDER BY askedAt DESC")
    fun observeAlerts(): Flow<List<SiteAlertEntity>>

    @Query("SELECT * FROM site_alert WHERE site = :site")
    fun observeAlert(site: String): Flow<SiteAlertEntity?>

    @Query("DELETE FROM site_notification WHERE postedAt < :before")
    suspend fun deleteOlderThan(before: Long)
}
