package io.github.inq_o.phonedoctor.core.notifications.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.inq_o.phonedoctor.core.notifications.Notifier
import io.github.inq_o.phonedoctor.core.notifications.SystemNotifier

@Module
@InstallIn(SingletonComponent::class)
interface NotificationsModule {
    @Binds
    fun bindsNotifier(impl: SystemNotifier): Notifier
}
