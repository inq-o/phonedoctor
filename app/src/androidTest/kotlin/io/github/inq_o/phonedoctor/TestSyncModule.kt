package io.github.inq_o.phonedoctor

import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import io.github.inq_o.phonedoctor.core.data.ScanScheduler
import io.github.inq_o.phonedoctor.core.data.test.FakeScanScheduler
import io.github.inq_o.phonedoctor.sync.work.di.SyncModule

/** 기기 테스트에서는 WorkManager를 띄우지 않는다 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [SyncModule::class])
interface TestSyncModule {
    @Binds
    fun bindScanScheduler(fake: FakeScanScheduler): ScanScheduler
}
