package io.github.inq_o.phonedoctor.sync.work.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.inq_o.phonedoctor.core.data.DeclineNudgeScheduler
import io.github.inq_o.phonedoctor.core.data.FalsePositiveReporter
import io.github.inq_o.phonedoctor.core.data.FamilySyncScheduler
import io.github.inq_o.phonedoctor.core.data.PushTokenSync
import io.github.inq_o.phonedoctor.core.data.ScanScheduler
import io.github.inq_o.phonedoctor.sync.work.WorkManagerDeclineNudgeScheduler
import io.github.inq_o.phonedoctor.sync.work.WorkManagerFalsePositiveReporter
import io.github.inq_o.phonedoctor.sync.work.WorkManagerFamilySyncScheduler
import io.github.inq_o.phonedoctor.sync.work.WorkManagerPushTokenSync
import io.github.inq_o.phonedoctor.sync.work.WorkManagerScanScheduler

@Module
@InstallIn(SingletonComponent::class)
interface SyncModule {
    @Binds
    fun bindsScanScheduler(impl: WorkManagerScanScheduler): ScanScheduler

    @Binds
    fun bindsPushTokenSync(impl: WorkManagerPushTokenSync): PushTokenSync

    @Binds
    fun bindsFamilySyncScheduler(impl: WorkManagerFamilySyncScheduler): FamilySyncScheduler

    @Binds
    fun bindsFalsePositiveReporter(impl: WorkManagerFalsePositiveReporter): FalsePositiveReporter

    @Binds
    fun bindsDeclineNudgeScheduler(impl: WorkManagerDeclineNudgeScheduler): DeclineNudgeScheduler
}
