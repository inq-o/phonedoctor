package io.github.inq_o.phonedoctor.core.network.di

import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.inq_o.phonedoctor.core.network.AuthTokens
import io.github.inq_o.phonedoctor.core.network.BuildConfig
import io.github.inq_o.phonedoctor.core.network.FirebaseAuthTokens
import io.github.inq_o.phonedoctor.core.network.PhoneDoctorNetworkDataSource
import io.github.inq_o.phonedoctor.core.network.networkClient
import io.github.inq_o.phonedoctor.core.network.networkDataSource
import io.github.inq_o.phonedoctor.core.network.networkJson
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class NetworkModule {
    @Binds
    abstract fun bindAuthTokens(impl: FirebaseAuthTokens): AuthTokens

    companion object {
        @Provides
        @Singleton
        fun provideNetworkDataSource(tokens: AuthTokens): PhoneDoctorNetworkDataSource =
            networkDataSource(BuildConfig.API_BASE_URL, networkClient(tokens), networkJson)
    }
}
