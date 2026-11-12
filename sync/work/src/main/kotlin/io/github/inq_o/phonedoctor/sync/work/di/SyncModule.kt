package io.github.inq_o.phonedoctor.sync.work.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.inq_o.phonedoctor.core.data.ScanScheduler
import io.github.inq_o.phonedoctor.sync.work.WorkManagerScanScheduler

@Module
@InstallIn(SingletonComponent::class)
interface SyncModule {
    @Binds
    fun bindsScanScheduler(impl: WorkManagerScanScheduler): ScanScheduler
}
