package io.github.inq_o.phonedoctor.core.packages.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.github.inq_o.phonedoctor.core.packages.AndroidInstalledApps
import io.github.inq_o.phonedoctor.core.packages.AppUninstaller
import io.github.inq_o.phonedoctor.core.packages.InstalledApps
import io.github.inq_o.phonedoctor.core.packages.PackageInstallerUninstaller

@Module
@InstallIn(SingletonComponent::class)
interface PackagesModule {
    @Binds
    fun bindsInstalledApps(impl: AndroidInstalledApps): InstalledApps

    @Binds
    fun bindsAppUninstaller(impl: PackageInstallerUninstaller): AppUninstaller
}
