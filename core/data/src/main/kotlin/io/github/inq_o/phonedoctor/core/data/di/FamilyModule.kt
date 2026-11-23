package io.github.inq_o.phonedoctor.core.data.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.inq_o.phonedoctor.core.data.AndroidDeviceInfo
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

    companion object {
        @Provides
        @FamilyEnabled
        fun providesFamilyEnabled(): Boolean = FamilyFeature.ENABLED
    }
}
