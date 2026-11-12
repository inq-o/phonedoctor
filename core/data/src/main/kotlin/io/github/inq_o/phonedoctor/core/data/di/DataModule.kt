package io.github.inq_o.phonedoctor.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.inq_o.phonedoctor.core.data.DefaultSymptomRepository
import io.github.inq_o.phonedoctor.core.data.DefaultUserSettingsRepository
import io.github.inq_o.phonedoctor.core.data.SymptomRepository
import io.github.inq_o.phonedoctor.core.data.UserSettingsRepository
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
interface DataModule {
    @Singleton
    @Binds
    fun bindsSymptomRepository(impl: DefaultSymptomRepository): SymptomRepository

    @Singleton
    @Binds
    fun bindsUserSettingsRepository(impl: DefaultUserSettingsRepository): UserSettingsRepository
}
