package com.catsmoker.app.system.di

import android.content.Context
import com.catsmoker.app.features.gamingtools.tools.audio.AudioBoostController
import com.catsmoker.app.features.logs.LogcatStreamer
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
    fun provideAudioBoostController(@ApplicationContext context: Context): AudioBoostController =
        AudioBoostController(context)

    /**
     * The single reader behind [com.catsmoker.app.features.logs.AppLogCollector]:
     * provided (not constructed inline) so tests can substitute a fake process.
     */
    @Provides
    @Singleton
    fun provideLogcatStreamer(): LogcatStreamer = LogcatStreamer()
}
