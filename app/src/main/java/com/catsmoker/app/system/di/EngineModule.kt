package com.catsmoker.app.system.di

import android.content.Context
import com.catsmoker.app.features.gamingtools.engine.DeviceDiagnosticManager
import com.catsmoker.app.features.gamingtools.engine.GamingEngine
import com.catsmoker.app.features.main.engine.MetricsEngine
import com.catsmoker.app.system.shell.ShellRunner
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object EngineModule {

    @Provides
    @Singleton
    fun provideMetricsEngine(
        @ApplicationContext context: Context,
        shellRunner: ShellRunner
    ): MetricsEngine = MetricsEngine(context, shellRunner)

    @Provides
    @Singleton
    fun provideGamingEngine(
        @ApplicationContext context: Context,
        shellRunner: ShellRunner,
        deviceDiagnosticManager: DeviceDiagnosticManager
    ): GamingEngine = GamingEngine(context, shellRunner, deviceDiagnosticManager)
}
