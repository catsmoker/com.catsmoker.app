package com.catsmoker.app.features.spoofdevice.root

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.catsmoker.app.shared.data.model.LSPosedConfig
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class LSPosedModule : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod("android.app.Application", lpparam.classLoader, "attach", Context::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as? Context ?: return
                    if (app.packageName == "com.catsmoker.app") return
                    val config = readConfig(app.contentResolver) ?: return
                    applyDeviceProps(config.deviceProps)
                }
            })
        } catch (_: Throwable) {}
    }

    private class ModuleConfig(val deviceProps: Map<String, String>)

    private fun readConfig(resolver: ContentResolver): ModuleConfig? {
        return try {
            val rawProps = Settings.Global.getString(resolver, LSPosedConfig.KEY_GLOBAL_DEVICE_PROPS_B64)
            val deviceProps = if (!rawProps.isNullOrEmpty()) LSPosedConfig.parseDeviceProps(decodeBase64(rawProps)) else LSPosedConfig.DEFAULT_DEVICE_PROPS
            ModuleConfig(deviceProps)
        } catch (_: Throwable) { null }
    }

    private fun decodeBase64(value: String): String = try { String(Base64.decode(value, Base64.NO_WRAP), Charsets.UTF_8) } catch (_: Exception) { "" }

    private fun applyDeviceProps(props: Map<String, String>) {
        props["MANUFACTURER"]?.let { XposedHelpers.setStaticObjectField(Build::class.java, "MANUFACTURER", it) }
        props["MODEL"]?.let { XposedHelpers.setStaticObjectField(Build::class.java, "MODEL", it) }
        props["BRAND"]?.let { XposedHelpers.setStaticObjectField(Build::class.java, "BRAND", it) }
        props["PRODUCT"]?.let { XposedHelpers.setStaticObjectField(Build::class.java, "PRODUCT", it) }
        props["DEVICE"]?.let { XposedHelpers.setStaticObjectField(Build::class.java, "DEVICE", it) }
    }
}
