package com.catsmoker.app.system

import android.app.Application
import com.catsmoker.app.BuildConfig
import com.catsmoker.app.system.shell.ShellRunner
import com.startapp.sdk.adsbase.StartAppSDK
import com.topjohnwu.superuser.Shell
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class CatsmokerApp : Application() {

    @Inject
    lateinit var shellRunner: ShellRunner

    override fun onCreate() {
        super.onCreate()
        
        // Initialize Start.io SDK
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val adsEnabled = prefs.getBoolean("ads_enabled", true)
        val appId = BuildConfig.STARTIO_APP_ID
        if (appId.isNotEmpty()) {
            StartAppSDK.init(this, appId, true)
            StartAppSDK.enableReturnAds(adsEnabled)
            // Demo ID: 205489527
            if (appId == "205489527") {
                StartAppSDK.setTestAdsEnabled(true)
            }
        }
        
        Shell.setDefaultBuilder(Shell.Builder.create()
            .setFlags(Shell.FLAG_REDIRECT_STDERR)
            .setTimeout(30))
            
        shellRunner.refreshShizukuPermission()
    }
}
