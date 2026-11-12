package io.github.inq_o.phonedoctor.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [SymptomEventEntity::class, DetectorStateEntity::class, ScanRunEntity::class, AlertLocalEntity::class],
    version = 1,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun symptomEventDao(): SymptomEventDao
    abstract fun detectorStateDao(): DetectorStateDao
    abstract fun scanRunDao(): ScanRunDao
    abstract fun alertLocalDao(): AlertLocalDao
}
