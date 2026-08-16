package com.catsmoker.app.system.di

import com.catsmoker.app.system.shell.ShizukuManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ShizukuModule {
    @Provides
    @Singleton
    fun provideShizukuManager(): ShizukuManager = ShizukuManager()
}
