package io.github.inq_o.phonedoctor.core.usage.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.inq_o.phonedoctor.core.usage.AndroidUsageAccess
import io.github.inq_o.phonedoctor.core.usage.AndroidUsageEventsSource
import io.github.inq_o.phonedoctor.core.usage.UsageAccess
import io.github.inq_o.phonedoctor.core.usage.UsageEventsSource

@Module
@InstallIn(SingletonComponent::class)
interface UsageModule {
    @Binds
    fun bindsUsageAccess(impl: AndroidUsageAccess): UsageAccess

    @Binds
    fun bindsUsageEventsSource(impl: AndroidUsageEventsSource): UsageEventsSource
}
