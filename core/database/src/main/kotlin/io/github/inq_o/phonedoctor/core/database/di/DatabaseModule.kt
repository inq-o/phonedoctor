package io.github.inq_o.phonedoctor.core.database.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.inq_o.phonedoctor.core.database.AlertLocalDao
import io.github.inq_o.phonedoctor.core.database.AppDatabase
import io.github.inq_o.phonedoctor.core.database.DetectorStateDao
import io.github.inq_o.phonedoctor.core.database.ScanRunDao
import io.github.inq_o.phonedoctor.core.database.SymptomEventDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
class DatabaseModule {
    @Provides
    fun provideSymptomEventDao(db: AppDatabase): SymptomEventDao = db.symptomEventDao()

    @Provides
    fun provideDetectorStateDao(db: AppDatabase): DetectorStateDao = db.detectorStateDao()

    @Provides
    fun provideScanRunDao(db: AppDatabase): ScanRunDao = db.scanRunDao()

    @Provides
    fun provideAlertLocalDao(db: AppDatabase): AlertLocalDao = db.alertLocalDao()

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext appContext: Context): AppDatabase =
        Room.databaseBuilder(appContext, AppDatabase::class.java, "phonedoctor.db").build()
}
