package com.catsmoker.app.features.gamingtools.engine

import android.content.Context
import android.os.Build
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceDiagnosticManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun getMaxHardwareRefreshRate(): Float {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.refreshRate ?: 60f
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.refreshRate
        }
    }

    fun isVivoOrIqoo(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        return manufacturer.contains("vivo") || manufacturer.contains("iqoo") ||
            brand.contains("vivo") || brand.contains("iqoo")
    }
}
