package io.github.inq_o.phonedoctor.core.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

/** 판 2: 확인 요청에 서버 요청 사본 열, 보호자 허용 목록(allow_entry) */
@Database(
    entities = [SymptomEventEntity::class, DetectorStateEntity::class, ScanRunEntity::class, AlertLocalEntity::class, AllowEntryEntity::class],
    version = 2,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun symptomEventDao(): SymptomEventDao
    abstract fun detectorStateDao(): DetectorStateDao
    abstract fun scanRunDao(): ScanRunDao
    abstract fun alertLocalDao(): AlertLocalDao
    abstract fun allowEntryDao(): AllowEntryDao
}
