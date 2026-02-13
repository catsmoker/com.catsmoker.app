package com.catsmoker.app.spoofing

import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class LSPosedModule : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val packageName = lpparam.packageName

        // Activation check for the app itself
        if (MODULE_PACKAGE == packageName) markModuleAsActive(lpparam)

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

        if (packageName in config.targetPackages) {
            spoofDevice(packageName, config.deviceProps)
        }
    }

    private fun markModuleAsActive(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.setStaticBooleanField(
                XposedHelpers.findClass("com.catsmoker.app.spoofing.RootActivity", lpparam.classLoader),
                "isModuleActive",
                true
            )
            XposedBridge.log("$TAG: Module activated for ${lpparam.packageName}")
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: Failed to set active flag for CatSmoker App: ${e.message}")
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
        val targetPackages = LSPosedConfig.parseTargetPackages(
            prefs.getString(LSPosedConfig.KEY_TARGET_PACKAGES, null)
        ).ifEmpty { LSPosedConfig.DEFAULT_TARGET_PACKAGES }
        val deviceProps = LSPosedConfig.parseDeviceProps(
            prefs.getString(LSPosedConfig.KEY_DEVICE_PROPS, null)
        ).ifEmpty { LSPosedConfig.DEFAULT_DEVICE_PROPS }

        return ModuleConfig(enabled, targetPackages, deviceProps)
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
