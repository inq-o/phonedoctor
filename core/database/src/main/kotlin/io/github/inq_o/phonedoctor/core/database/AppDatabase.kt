package io.github.inq_o.phonedoctor.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase

/** 판 2: 확인 요청에 서버 요청 사본 열, 보호자 허용 목록(allow_entry). 판 3: Chrome 사이트 알림 (US10) */
@Database(
    entities = [
        SymptomEventEntity::class, DetectorStateEntity::class, ScanRunEntity::class, AlertLocalEntity::class, AllowEntryEntity::class,
        SiteNotificationEntity::class, SiteAlertEntity::class,
    ],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2, spec = AppDatabase.To2::class), AutoMigration(from = 2, to = 3)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun symptomEventDao(): SymptomEventDao
    abstract fun detectorStateDao(): DetectorStateDao
    abstract fun scanRunDao(): ScanRunDao
    abstract fun alertLocalDao(): AlertLocalDao
    abstract fun allowEntryDao(): AllowEntryDao
    abstract fun siteNotificationDao(): SiteNotificationDao

    /** 판 1에서 이미 가족과 연결한 폰의 열린 요청도 서버에 보내게 표시한다 */
    class To2 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE alert_local SET needsSync = 1 WHERE status = 'OPEN'")
        }
    }
}
