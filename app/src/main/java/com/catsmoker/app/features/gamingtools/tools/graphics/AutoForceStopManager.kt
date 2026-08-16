package com.catsmoker.app.features.gamingtools.tools.graphics

import android.content.Context
import androidx.core.content.edit

class AutoForceStopManager(context: Context) {
    private val prefs = context.getSharedPreferences("AutoForceStopPrefs", Context.MODE_PRIVATE)

    fun getSelectedPackages(): Set<String> {
        return prefs.getStringSet("selected_packages", emptySet()) ?: emptySet()
    }

    fun togglePackage(packageName: String): Set<String> {
        val current = getSelectedPackages().toMutableSet()
        if (current.contains(packageName)) {
            current.remove(packageName)
        } else {
            current.add(packageName)
        }
        prefs.edit { putStringSet("selected_packages", current) }
        return current
    }

    fun isEnabled(): Boolean {
        return getSelectedPackages().isNotEmpty()
    }
}
