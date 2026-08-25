package com.catsmoker.app.features.gamingtools.tools.graphics

import android.content.ContentResolver
import android.content.Context
import android.provider.Settings
import com.catsmoker.app.system.shell.ShellRunner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads and writes the developer-options settings the gaming tools care about.
 *
 * `settings put` succeeds silently, so a write is confirmed by reading the value back through
 * [ContentResolver] rather than by inspecting command output — that also covers OEM ROMs which
 * accept the command but silently ignore the key.
 */
@Singleton
class DeveloperOptionsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellRunner: ShellRunner
) {
    private val contentResolver: ContentResolver = context.contentResolver

    private enum class Namespace(val cli: String) { GLOBAL("global"), SECURE("secure"), SYSTEM("system") }

    private fun readRaw(namespace: Namespace, key: String): String? = try {
        when (namespace) {
            Namespace.GLOBAL -> Settings.Global.getString(contentResolver, key)
            Namespace.SECURE -> Settings.Secure.getString(contentResolver, key)
            Namespace.SYSTEM -> Settings.System.getString(contentResolver, key)
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Writes [value] (or deletes the key when null) and confirms the change stuck.
     * @return true when the setting now holds the requested value.
     */
    private suspend fun put(namespace: Namespace, key: String, value: String?): Boolean =
        withContext(Dispatchers.IO) {
            val result = if (value == null) {
                shellRunner.execSafeResult("settings", "delete", namespace.cli, key)
            } else {
                shellRunner.execSafeResult("settings", "put", namespace.cli, key, value)
            }

            val readBack = readRaw(namespace, key)
            val applied = if (value == null) readBack.isNullOrBlank() else readBack == value
            // A non-zero exit with the right value still counts (some ROMs log to stderr and
            // return 1); the read-back is the authority.
            applied || (result.isSuccess && readBack == value)
        }

    private suspend fun putInt(namespace: Namespace, key: String, value: Int): Boolean =
        put(namespace, key, value.toString())

    // "Don't keep activities" and the background-process limit both live on GamingEngine, which
    // owns their StateFlows and merges activity_manager_constants non-destructively.

    suspend fun getAngleDriverSelections(): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val pkgsCsv = Settings.Global.getString(contentResolver, ANGLE_PKGS_KEY)
            val valuesCsv = Settings.Global.getString(contentResolver, ANGLE_VALUES_KEY)
            if (pkgsCsv.isNullOrBlank() || valuesCsv.isNullOrBlank()) return@withContext emptyMap()
            val pkgs = pkgsCsv.split(",")
            val values = valuesCsv.split(",")
            if (pkgs.size != values.size) return@withContext emptyMap()
            pkgs.zip(values).toMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    suspend fun setAngleDriverSelection(packageName: String, driver: String?): Boolean {
        val current = getAngleDriverSelections().toMutableMap()
        if (driver == null) current.remove(packageName) else current[packageName] = driver

        val pkgsCsv = current.keys.joinToString(",")
        val valuesCsv = current.values.joinToString(",")

        val pkgsResult = put(Namespace.GLOBAL, ANGLE_PKGS_KEY, pkgsCsv.ifEmpty { null })
        val valuesResult = put(Namespace.GLOBAL, ANGLE_VALUES_KEY, valuesCsv.ifEmpty { null })

        // The debug package is what makes the override take effect for the next launch.
        // Clear it again when the last selection is removed, otherwise it pins a stale package.
        if (driver != null) {
            put(Namespace.GLOBAL, ANGLE_DEBUG_PKG_KEY, packageName)
        } else if (current.isEmpty()) {
            put(Namespace.GLOBAL, ANGLE_DEBUG_PKG_KEY, null)
        }

        return pkgsResult && valuesResult
    }

    companion object {
        private const val ANGLE_PKGS_KEY = "angle_gl_driver_selection_pkgs"
        private const val ANGLE_VALUES_KEY = "angle_gl_driver_selection_values"
        private const val ANGLE_DEBUG_PKG_KEY = "angle_debug_package"
    }
}
