package com.catsmoker.app.spoofing

import android.os.Build
import android.provider.Settings
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.io.File
import java.util.Base64

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
        XposedBridge.log(
            "$TAG: Spoofing $packageName model=${deviceProps["MODEL"]} manufacturer=${deviceProps["MANUFACTURER"]}"
        )

        for (entry in deviceProps.entries) {
            try {
                XposedHelpers.setStaticObjectField(Build::class.java, entry.key, entry.value)
            } catch (e: Throwable) {
                XposedBridge.log("$TAG: Failed to spoof ${entry.key} for $packageName: ${e.message}")
            }
        }
        spoofSystemProperties(deviceProps)
    }

    private fun spoofSystemProperties(deviceProps: Map<String, String>) {
        val model = deviceProps["MODEL"] ?: return
        val manufacturer = deviceProps["MANUFACTURER"] ?: return
        val brand = deviceProps["BRAND"] ?: manufacturer
        val product = deviceProps["PRODUCT"] ?: model
        val device = deviceProps["DEVICE"] ?: product

        val propOverrides = mapOf(
            "ro.product.manufacturer" to manufacturer,
            "ro.product.model" to model,
            "ro.product.brand" to brand,
            "ro.product.name" to product,
            "ro.product.device" to device,
            "ro.vendor.product.manufacturer" to manufacturer,
            "ro.vendor.product.model" to model,
            "ro.vendor.product.brand" to brand,
            "ro.vendor.product.name" to product,
            "ro.vendor.product.device" to device,
            "ro.system.product.manufacturer" to manufacturer,
            "ro.system.product.model" to model,
            "ro.system.product.brand" to brand,
            "ro.system.product.name" to product,
            "ro.system.product.device" to device
        )

        runCatching {
            XposedHelpers.findAndHookMethod(
                "android.os.SystemProperties",
                null,
                "get",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        val value = propOverrides[key] ?: return
                        param.result = value
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                "android.os.SystemProperties",
                null,
                "get",
                String::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args[0] as? String ?: return
                        val value = propOverrides[key] ?: return
                        param.result = value
                    }
                }
            )
        }.onFailure {
            XposedBridge.log("$TAG: Failed to hook SystemProperties: ${it.message}")
        }
    }

    private fun loadConfig(): ModuleConfig {
        val prefs = loadReadablePrefs()
        prefs?.reload()

        val enabledFromPrefs = prefs?.getBoolean(LSPosedConfig.KEY_ENABLED, true)
        val targetsRawFromPrefs = prefs?.getString(LSPosedConfig.KEY_TARGET_PACKAGES, null)
        val propsRawFromPrefs = prefs?.getString(LSPosedConfig.KEY_DEVICE_PROPS, null)

        val globalFallback = loadConfigFromGlobalSettings()

        val enabled = enabledFromPrefs ?: globalFallback?.enabled ?: true
        val targetsRaw = if (!targetsRawFromPrefs.isNullOrBlank()) targetsRawFromPrefs else globalFallback?.targetsRaw
        val propsRaw = if (!propsRawFromPrefs.isNullOrBlank()) propsRawFromPrefs else globalFallback?.propsRaw

        val targetPackages = LSPosedConfig.parseTargetPackages(targetsRaw)
            .ifEmpty { LSPosedConfig.DEFAULT_TARGET_PACKAGES }
        val deviceProps = LSPosedConfig.parseDeviceProps(propsRaw)
            .ifEmpty { LSPosedConfig.DEFAULT_DEVICE_PROPS }

        XposedBridge.log(
            "$TAG: loadConfig enabled=$enabled hasTargets=${!targetsRaw.isNullOrBlank()} " +
                "hasProps=${!propsRaw.isNullOrBlank()} prefs=${prefs != null} " +
                "globalFallback=${globalFallback != null}"
        )

        return ModuleConfig(enabled, targetPackages, deviceProps)
    }

    private fun loadReadablePrefs(): XSharedPreferences? {
        val byName = runCatching { XSharedPreferences(MODULE_PACKAGE, LSPosedConfig.PREFS_NAME) }.getOrNull()
        if (byName != null) {
            byName.reload()
            XposedBridge.log("$TAG: loaded prefs via package/name API canRead=${byName.file.canRead()}")
            return byName
        }

        val candidates = listOf(
            "/data/user_de/0/$MODULE_PACKAGE/shared_prefs/${LSPosedConfig.PREFS_NAME}.xml",
            "/data/user/0/$MODULE_PACKAGE/shared_prefs/${LSPosedConfig.PREFS_NAME}.xml"
        )
        for (path in candidates) {
            val file = File(path)
            if (!file.exists()) continue
            val prefs = XSharedPreferences(file)
            prefs.reload()
            if (prefs.file.canRead() || prefs.contains(LSPosedConfig.KEY_ENABLED)) {
                XposedBridge.log("$TAG: loaded prefs from $path")
                return prefs
            }
            XposedBridge.log(
                "$TAG: prefs candidate unreadable or empty path=$path canRead=${prefs.file.canRead()}"
            )
        }
        XposedBridge.log("$TAG: no readable prefs file, using defaults")
        return null
    }

    private fun loadConfigFromGlobalSettings(): GlobalConfigData? {
        val app = runCatching {
            XposedHelpers.callStaticMethod(
                XposedHelpers.findClass("android.app.ActivityThread", null),
                "currentApplication"
            ) as? android.app.Application
        }.getOrNull() ?: return null
        val resolver = app.contentResolver ?: return null
        return runCatching {
            val enabledRaw = Settings.Global.getString(resolver, LSPosedConfig.KEY_GLOBAL_ENABLED)
            val targetsB64 = Settings.Global.getString(resolver, LSPosedConfig.KEY_GLOBAL_TARGET_PACKAGES_B64)
            val propsB64 = Settings.Global.getString(resolver, LSPosedConfig.KEY_GLOBAL_DEVICE_PROPS_B64)
            val targetsRaw = decodeBase64(targetsB64)
            val propsRaw = decodeBase64(propsB64)
            val enabled = enabledRaw?.toIntOrNull()?.let { it != 0 }
            if (enabled == null && targetsRaw.isNullOrBlank() && propsRaw.isNullOrBlank()) {
                null
            } else {
                GlobalConfigData(enabled, targetsRaw, propsRaw)
            }
        }.getOrNull()
    }

    private fun decodeBase64(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            String(Base64.getDecoder().decode(raw), Charsets.UTF_8)
        }.getOrNull()
    }

    private data class ModuleConfig(
        val enabled: Boolean,
        val targetPackages: Set<String>,
        val deviceProps: Map<String, String>
    )

    private data class GlobalConfigData(
        val enabled: Boolean?,
        val targetsRaw: String?,
        val propsRaw: String?
    )

    companion object {
        private const val TAG = "CatSmokerModule"
        private const val MODULE_PACKAGE = "com.catsmoker.app"
    }
}
