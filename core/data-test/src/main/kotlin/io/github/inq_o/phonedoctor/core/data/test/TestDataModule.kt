package io.github.inq_o.phonedoctor.core.data.test

import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import io.github.inq_o.phonedoctor.core.data.SymptomRepository
import io.github.inq_o.phonedoctor.core.data.UserSettingsRepository
import io.github.inq_o.phonedoctor.core.data.di.DataModule

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [DataModule::class],
)
interface TestDataModule {
    @Binds
    fun bindSymptomRepository(fake: FakeSymptomRepository): SymptomRepository

    @Binds
    fun bindUserSettingsRepository(fake: FakeUserSettingsRepository): UserSettingsRepository
}
