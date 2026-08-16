package com.catsmoker.app.features.gamingtools.tools.graphics

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import com.catsmoker.app.features.gamingtools.engine.GamingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeveloperOptionsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gamingEngine: GamingEngine
) {
    private val contentResolver: ContentResolver = context.contentResolver

    private suspend fun putGlobalInt(key: String, value: Int): Boolean {
        return gamingEngine.executeSafe("settings", "put", "global", key, value.toString()).isNotBlank()
    }

    private suspend fun putGlobalString(key: String, value: String?): Boolean {
        return if (value == null) {
            gamingEngine.executeSafe("settings", "delete", "global", key).isNotBlank()
        } else {
            gamingEngine.executeSafe("settings", "put", "global", key, value).isNotBlank()
        }
    }

    private suspend fun putSecureInt(key: String, value: Int): Boolean {
        return gamingEngine.executeSafe("settings", "put", "secure", key, value.toString()).isNotBlank()
    }

    private suspend fun putSystemInt(key: String, value: Int): Boolean {
        return gamingEngine.executeSafe("settings", "put", "system", key, value.toString()).isNotBlank()
    }

    // "Don't keep activities"
    fun isAlwaysFinishActivitiesEnabled(): Boolean {
        return try {
            Settings.Global.getInt(contentResolver, Settings.Global.ALWAYS_FINISH_ACTIVITIES, 0) == 1
        } catch (e: Exception) {
            false
        }
    }

    suspend fun setAlwaysFinishActivities(enabled: Boolean): Boolean {
        return putGlobalInt(Settings.Global.ALWAYS_FINISH_ACTIVITIES, if (enabled) 1 else 0)
    }

    // Limit background processes

    fun isBackgroundProcessLimitEnabled(): Boolean {
        return try {
            val value = Settings.Global.getString(contentResolver, AM_CONSTANTS_KEY) ?: return false
            value.contains("max_cached_processes=$MAX_CACHED_PROCESSES_LIMITED")
        } catch (e: Exception) {
            false
        }
    }

    suspend fun setBackgroundProcessLimit(limited: Boolean): Boolean {
        val value = if (limited) "max_cached_processes=$MAX_CACHED_PROCESSES_LIMITED" else null
        return putGlobalString(AM_CONSTANTS_KEY, value)
    }

    fun isFancyImeAnimationsDisabled(): Boolean {
        return try {
            Settings.Secure.getInt(contentResolver, FANCY_IME_ANIMATIONS_KEY, 1) == 0
        } catch (e: Exception) {
            false
        }
    }

    suspend fun setFancyImeAnimations(disabled: Boolean): Boolean {
        return putSecureInt(FANCY_IME_ANIMATIONS_KEY, if (disabled) 0 else 1)
    }

    fun isClockSecondsEnabled(): Boolean {
        return try {
            Settings.Secure.getInt(contentResolver, CLOCK_SECONDS_KEY, 0) == 1
        } catch (e: Exception) {
            false
        }
    }

    suspend fun setClockSeconds(enabled: Boolean): Boolean {
        return putSecureInt(CLOCK_SECONDS_KEY, if (enabled) 1 else 0)
    }

    suspend fun getAngleDriverSelections(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val pkgsCsv = Settings.Global.getString(contentResolver, ANGLE_PKGS_KEY)
            val valuesCsv = Settings.Global.getString(contentResolver, ANGLE_VALUES_KEY)
            if (pkgsCsv.isNullOrBlank() || valuesCsv.isNullOrBlank()) return@withContext emptyMap()
            val pkgs = pkgsCsv.split(",")
            val values = valuesCsv.split(",")
            if (pkgs.size != values.size) return@withContext emptyMap()
            pkgs.zip(values).toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    suspend fun setAngleDriverSelection(packageName: String, driver: String?): Boolean {
        val current = getAngleDriverSelections().toMutableMap()
        if (driver == null) {
            current.remove(packageName)
        } else {
            current[packageName] = driver
        }

        val pkgsCsv = current.keys.joinToString(",")
        val valuesCsv = current.values.joinToString(",")

        val pkgsResult = putGlobalString(ANGLE_PKGS_KEY, pkgsCsv.ifEmpty { null })
        val valuesResult = putGlobalString(ANGLE_VALUES_KEY, valuesCsv.ifEmpty { null })
        
        // Also set debug package for active override
        if (driver != null) {
            putGlobalString(ANGLE_DEBUG_PKG_KEY, packageName)
        }
        
        return pkgsResult && valuesResult
    }

    companion object {
        private const val AM_CONSTANTS_KEY = "activity_manager_constants"
        private const val MAX_CACHED_PROCESSES_LIMITED = 1
        private const val FANCY_IME_ANIMATIONS_KEY = "fancy_ime_animations"
        private const val CLOCK_SECONDS_KEY = "clock_seconds"
        private const val ANGLE_PKGS_KEY = "angle_gl_driver_selection_pkgs"
        private const val ANGLE_VALUES_KEY = "angle_gl_driver_selection_values"
        private const val ANGLE_DEBUG_PKG_KEY = "angle_debug_package"
    }
}
