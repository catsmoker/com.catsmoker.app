package com.catsmoker.app.core

import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class LSPosedModule : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        val packageName = lpparam.packageName

        // Activation check for the app itself
        if ("com.catsmoker.app" == packageName) {
            try {
                XposedHelpers.setStaticBooleanField(
                    XposedHelpers.findClass("com.catsmoker.app.features.RootActivity", lpparam.classLoader),
                    "isModuleActive",
                    true
                )
                XposedBridge.log("$TAG: Module activated for $packageName")
            } catch (e: Throwable) {
                XposedBridge.log("$TAG: Failed to set active flag for CatSmoker App: ${e.message}")
            }
        }

        // Fast check if the package is in our target list
        val config = loadConfig()
        if (packageName == MODULE_PACKAGE) {
            XposedBridge.log(
                "$TAG: config enabled=${config.enabled} targets=${config.targetPackages.size} " +
                    "props=${config.deviceProps.size} " +
                    "model=${config.deviceProps["MODEL"]} manufacturer=${config.deviceProps["MANUFACTURER"]}"
            )
        }
        if (!config.enabled) return

        if (config.targetPackages.contains(packageName)) {
            spoofDevice(packageName, config.deviceProps)
        }
    }

    private fun spoofDevice(packageName: String, deviceProps: Map<String, String>) {
        XposedBridge.log("$TAG: Spoofing $packageName as OnePlus Pad 3")

        for (entry in deviceProps.entries) {
            try {
                XposedHelpers.setStaticObjectField(Build::class.java, entry.key, entry.value)
            } catch (e: Throwable) {
                XposedBridge.log("$TAG: Failed to spoof ${entry.key} for $packageName: ${e.message}")
            }
        }
    }

    private fun loadConfig(): ModuleConfig {
        val prefs = XSharedPreferences(MODULE_PACKAGE, LSPosedConfig.PREFS_NAME)
        prefs.reload()

        val enabled = prefs.getBoolean(LSPosedConfig.KEY_ENABLED, true)
        val targetPackages = parseTargetPackages(
            prefs.getString(LSPosedConfig.KEY_TARGET_PACKAGES, null)
        ).ifEmpty { LSPosedConfig.DEFAULT_TARGET_PACKAGES }
        val deviceProps = parseDeviceProps(
            prefs.getString(LSPosedConfig.KEY_DEVICE_PROPS, null)
        ).ifEmpty { LSPosedConfig.DEFAULT_DEVICE_PROPS }

        return ModuleConfig(enabled, targetPackages, deviceProps)
    }

    private fun parseTargetPackages(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        val result = LinkedHashSet<String>()
        raw
            .split("\n", ",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { result.add(it) }
        return result
    }

    private fun parseDeviceProps(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            val idx = trimmed.indexOf('=')
            if (idx <= 0 || idx == trimmed.length - 1) return@forEach
            val key = trimmed.substring(0, idx).trim()
            val value = trimmed.substring(idx + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                result[key] = value
            }
        }
        return result
    }

    private data class ModuleConfig(
        val enabled: Boolean,
        val targetPackages: Set<String>,
        val deviceProps: Map<String, String>
    )

    companion object {
        private const val TAG = "CatSmokerModule"
        private const val MODULE_PACKAGE = "com.catsmoker.app"
    }
}



