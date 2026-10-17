package com.catsmoker.app.system

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.catsmoker.app.features.main.engine.MetricsEngine
import com.catsmoker.app.system.config.AppearanceStore
import com.catsmoker.app.system.config.LocaleHelper
import com.catsmoker.app.system.shell.ShellRunner
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Also WorkManager's [Configuration.Provider]: the default initializer is removed in the manifest
 * (see there for why), so this is the only place WorkManager can learn how to construct
 * `@HiltWorker` workers. It deliberately constructs the [Configuration] lazily per read rather
 * than in onCreate — WorkManager may not be touched until the first scheduled-work enqueue, long
 * after startup, and the factory is field-injected by then either way.
 */
@HiltAndroidApp
class CatsmokerApp : Application(), Configuration.Provider {

    @Inject
    lateinit var shellRunner: dagger.Lazy<ShellRunner>

    @Inject
    lateinit var metricsEngine: dagger.Lazy<MetricsEngine>

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /**
     * The app-level live log buffer (System Logs screen observes it). Started once
     * here — not from the screen — so collection runs while the process lives,
     * foreground or background. A daemon reader thread, not a Service: it dies
     * with the process, which is exactly its required lifetime.
     */
    @Inject
    lateinit var appLogCollector: com.catsmoker.app.features.logs.AppLogCollector

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun attachBaseContext(base: Context) {
        // Saved language applies to app-context resources too (services, notifications),
        // and primes the theme flow before any UI reads it.
        val wrapped = LocaleHelper.wrap(base)
        super.attachBaseContext(wrapped)
        AppearanceStore.init(wrapped)
    }

    override fun onCreate() {
        super.onCreate()
        
        applicationScope.launch(Dispatchers.Default) {
            // Trigger pre-warming of heavy dependencies while splash is showing
            try {
                metricsEngine.get()
                shellRunner.get()
            } catch (_: Exception) {}
        }

        // Live app-log collection starts with the process (own-UID logcat, no
        // privilege, nothing on disk). Off the main thread: first start blocks
        // ~200 ms on the reader's start grace.
        applicationScope.launch(Dispatchers.IO) {
            runCatching { appLogCollector.ensureStarted() }
        }

        // libsu 6.x redirects stderr only via this opt-in; the builder flag is
        // deprecated. ShellRunner relies on the merge (result.err stays empty).
        Shell.enableLegacyStderrRedirection = true
        Shell.setDefaultBuilder(Shell.Builder.create().setTimeout(30))
    }

    /**
     * Initializes heavy SDKs and privilege checks. 
     * Should be called when the UI is already visible and the main thread is idle.
     */
    fun initDeferredTasks() {
        applicationScope.launch(Dispatchers.IO) {
            // Wait for system to settle slightly but much shorter
            delay(500.milliseconds)

            // Pre-warm Hilt dependencies in the background
            metricsEngine.get()
            shellRunner.get()

            // Variant ads bootstrap: Start.io on full, AdMob on playstore.
            AdsInit.init(this@CatsmokerApp)
        }
    }
}
