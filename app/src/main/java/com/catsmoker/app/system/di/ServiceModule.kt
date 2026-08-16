package com.catsmoker.app.system.di

import android.content.Context
import com.catsmoker.app.features.gamingtools.tools.audio.BoostController
import com.catsmoker.app.features.gamingtools.tools.graphics.AutoForceStopManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ServiceModule {

    @Provides
    @Singleton
    fun provideBoostController(@ApplicationContext context: Context): BoostController =
        BoostController(context)

    @Provides
    @Singleton
    fun provideAutoForceStopManager(@ApplicationContext context: Context): AutoForceStopManager =
        AutoForceStopManager(context)
}
