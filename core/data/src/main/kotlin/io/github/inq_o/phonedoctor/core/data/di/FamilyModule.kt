package io.github.inq_o.phonedoctor.core.data.di

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.github.inq_o.phonedoctor.core.data.AndroidDeviceInfo
import io.github.inq_o.phonedoctor.core.data.AndroidProtectionStatus
import io.github.inq_o.phonedoctor.core.data.DefaultFamilyAlertSync
import io.github.inq_o.phonedoctor.core.data.FamilyAlertSync
import io.github.inq_o.phonedoctor.core.data.FamilyLinkEvents
import io.github.inq_o.phonedoctor.core.data.ProtectionStatus
import io.github.inq_o.phonedoctor.core.data.DefaultFamilyRepository
import io.github.inq_o.phonedoctor.core.data.DeviceInfo
import io.github.inq_o.phonedoctor.core.data.FamilyAccount
import io.github.inq_o.phonedoctor.core.data.FamilyEnabled
import io.github.inq_o.phonedoctor.core.data.FamilyRepository
import io.github.inq_o.phonedoctor.core.data.FirebaseFamilyAccount
import io.github.inq_o.phonedoctor.core.data.FirebasePushTokens
import io.github.inq_o.phonedoctor.core.data.PushTokens
import io.github.inq_o.phonedoctor.core.network.FamilyFeature
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal interface FamilyModule {
    @Singleton
    @Binds
    fun bindsFamilyRepository(impl: DefaultFamilyRepository): FamilyRepository

    @Binds
    fun bindsFamilyAccount(impl: FirebaseFamilyAccount): FamilyAccount

    @Binds
    fun bindsPushTokens(impl: FirebasePushTokens): PushTokens

    @Binds
    fun bindsDeviceInfo(impl: AndroidDeviceInfo): DeviceInfo

    @Binds
    fun bindsFamilyAlertSync(impl: DefaultFamilyAlertSync): FamilyAlertSync

    @Binds
    fun bindsFamilyLinkEvents(impl: DefaultFamilyAlertSync): FamilyLinkEvents

    @Binds
    fun bindsProtectionStatus(impl: AndroidProtectionStatus): ProtectionStatus

    companion object {
        @Provides
        @FamilyEnabled
        fun providesFamilyEnabled(@ApplicationContext context: Context): Boolean = FamilyFeature.isAvailable(context)
    }
}
