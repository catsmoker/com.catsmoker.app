package com.catsmoker.app.util

import android.content.Context
import android.provider.Settings
import com.catsmoker.app.core.GamingEngine

class DeveloperOptionsController(private val context: Context, private val engine: GamingEngine) {

    private val ANGLE_PKGS_KEY = "angle_gl_driver_selection_pkgs"
    private val ANGLE_VALUES_KEY = "angle_gl_driver_selection_values"

    fun getAngleDriverSelections(): Map<String, String> {
        return try {
            val cr = context.contentResolver
            val pkgsCsv = Settings.Global.getString(cr, ANGLE_PKGS_KEY)
            val valuesCsv = Settings.Global.getString(cr, ANGLE_VALUES_KEY)
            if (pkgsCsv.isNullOrBlank() || valuesCsv.isNullOrBlank()) return emptyMap()
            val pkgs = pkgsCsv.split(",")
            val values = valuesCsv.split(",")
            if (pkgs.size != values.size) return emptyMap()
            pkgs.zip(values).toMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    suspend fun setAngleDriverSelection(packageName: String, driver: String?) {
        val current = getAngleDriverSelections().toMutableMap()
        if (driver == null) {
            current.remove(packageName)
        } else {
            current[packageName] = driver
        }

        val pkgsCsv = current.keys.joinToString(",")
        val valuesCsv = current.values.joinToString(",")

        if (current.isEmpty()) {
            engine.execute("settings delete global $ANGLE_PKGS_KEY")
            engine.execute("settings delete global $ANGLE_VALUES_KEY")
        } else {
            engine.execute("settings put global $ANGLE_PKGS_KEY \"$pkgsCsv\"")
            engine.execute("settings put global $ANGLE_VALUES_KEY \"$valuesCsv\"")
        }
    }
}
