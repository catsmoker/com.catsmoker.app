package com.catsmoker.app.features.gamingtools.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EsportsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gamingEngine: GamingEngine,
    private val deviceDiagnosticManager: DeviceDiagnosticManager
) {
    suspend fun applyOptimizations(packageName: String?, uid: Int?): Boolean = withContext(Dispatchers.IO) {
        // 1. RAM Cache Trimming & ART Compaction
        gamingEngine.execute("pm trim-caches 4G")
        gamingEngine.execute("am compact background")
        runCatching { gamingEngine.execute("cmd pinner repin /system/framework/framework.jar") }

        // 2. CPU Priority & Memory Lock
        if (packageName != null) {
            gamingEngine.execute("cmd activity set-bg-restriction-level --user 0 $packageName unrestricted")
            gamingEngine.execute("am set-standby-bucket --user 0 $packageName active")
        }

        // 3. Network Firewall & Deep Doze Exemption
        if (uid != null) {
            gamingEngine.execute("cmd netpolicy add restrict-background-whitelist $uid")
            if (packageName != null) {
                gamingEngine.execute("cmd deviceidle whitelist +$packageName")
            }
        }
        gamingEngine.execute("cmd deviceidle force-idle")

        // 4. Performance Governor Lock
        gamingEngine.execute("cmd power set-fixed-performance-mode-enabled true")

        // 5. Refresh Rate Lock
        val maxHz = deviceDiagnosticManager.getMaxHardwareRefreshRate().toInt()
        gamingEngine.execute("settings put system peak_refresh_rate $maxHz")
        gamingEngine.execute("settings put system min_refresh_rate $maxHz")

        // 6. Touch Response Boost
        gamingEngine.execute("settings put system touch_response_speed 2")
        
        // 7. Per-game game mode settings
        if (packageName != null) {
            gamingEngine.execute("cmd game set --mode performance --fps $maxHz $packageName")
        }

        true
    }

    suspend fun revertOptimizations(packageName: String?, uid: Int?): Boolean = withContext(Dispatchers.IO) {
        if (packageName != null) {
            gamingEngine.execute("cmd activity set-bg-restriction-level --user 0 $packageName adaptive_bucket")
            gamingEngine.execute("am set-standby-bucket --user 0 $packageName working_set")
            gamingEngine.execute("cmd deviceidle whitelist -$packageName")
            gamingEngine.execute("cmd game reset --user 0 $packageName")
        }

        if (uid != null) {
            gamingEngine.execute("cmd netpolicy remove restrict-background-whitelist $uid")
        }

        gamingEngine.execute("cmd deviceidle unforce")
        gamingEngine.execute("cmd power set-fixed-performance-mode-enabled false")
        
        // Reset system settings to defaults
        gamingEngine.execute("settings delete system min_refresh_rate")
        gamingEngine.execute("settings delete system peak_refresh_rate")
        gamingEngine.execute("settings delete system touch_response_speed")

        true
    }
}
