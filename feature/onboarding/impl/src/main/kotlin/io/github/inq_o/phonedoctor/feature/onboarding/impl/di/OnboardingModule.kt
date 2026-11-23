package io.github.inq_o.phonedoctor.feature.onboarding.impl.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.inq_o.phonedoctor.feature.onboarding.impl.SiteAlertsEnabled
import io.github.inq_o.phonedoctor.feature.seniorsites.api.SiteAlertsFeature

@Module
@InstallIn(SingletonComponent::class)
object OnboardingModule {
    @Provides
    @SiteAlertsEnabled
    fun providesSiteAlertsEnabled(): Boolean = SiteAlertsFeature.ENABLED
}
