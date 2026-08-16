package com.catsmoker.app.shared.data.repository

import android.content.Context
import android.content.Intent
import com.catsmoker.app.shared.data.model.DevicePreset
import com.catsmoker.app.shared.data.model.DeviceProfile
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SpoofRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val storeFile = File(context.filesDir, "app_profiles.json")

    data class StoreData(
        val version: Int = 1,
        val profiles: MutableList<ProfileEntry> = mutableListOf(),
        val assignments: MutableMap<String, String> = mutableMapOf(),
        val globalProperties: MutableMap<String, String> = mutableMapOf()
    )

    data class ProfileEntry(
        val id: String,
        var name: String,
        val profile: DeviceProfile
    )

    private var cachedData: StoreData? = null

    suspend fun loadData(): StoreData = withContext(Dispatchers.IO) {
        if (cachedData != null) return@withContext cachedData!!
        
        val data = if (storeFile.exists()) {
            try {
                gson.fromJson(storeFile.readText(), StoreData::class.java)
            } catch (e: Exception) {
                createDefaultData()
            }
        } else {
            createDefaultData()
        }
        cachedData = data
        data
    }

    private fun createDefaultData(): StoreData {
        val defaultProfile = DeviceProfile(
            brand = "Google",
            manufacturer = "Google",
            model = "Pixel 8 Pro",
            productName = "husky",
            deviceCode = "husky",
            buildRelease = "14",
            buildSdk = 34
        ).apply { applyFallbacks() }
        
        return StoreData(
            profiles = mutableListOf(
                ProfileEntry(UUID.randomUUID().toString(), "Default Profile", defaultProfile)
            )
        )
    }

    suspend fun save() = withContext(Dispatchers.IO) {
        val data = cachedData ?: return@withContext
        storeFile.writeText(gson.toJson(data))
        // Broadcast for non-root apps or other components to reload
        context.sendBroadcast(Intent("com.catsmoker.app.action.CONFIG_CHANGED"))
    }

    suspend fun getProfileForPackage(packageName: String): DeviceProfile? {
        val data = loadData()
        val profileId = data.assignments[packageName] ?: return null
        return data.profiles.find { it.id == profileId }?.profile
    }

    fun createCurrentDevicePreset(): DevicePreset {
        val metrics = context.resources.displayMetrics
        val profile = DeviceProfile(
            brand = android.os.Build.BRAND,
            manufacturer = android.os.Build.MANUFACTURER,
            model = android.os.Build.MODEL,
            productName = android.os.Build.PRODUCT,
            deviceCode = android.os.Build.DEVICE,
            board = android.os.Build.BOARD,
            hardware = android.os.Build.HARDWARE,
            buildFingerprint = android.os.Build.FINGERPRINT,
            buildId = android.os.Build.ID,
            buildDisplayId = android.os.Build.DISPLAY,
            buildIncremental = android.os.Build.VERSION.INCREMENTAL,
            buildRelease = android.os.Build.VERSION.RELEASE,
            buildSdk = android.os.Build.VERSION.SDK_INT,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            screenDensity = metrics.densityDpi,
            timezone = java.util.TimeZone.getDefault().id,
            locale = java.util.Locale.getDefault().toLanguageTag(),
            bootloader = android.os.Build.BOOTLOADER
        ).apply {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                securityPatch = android.os.Build.VERSION.SECURITY_PATCH
            }
            applyFallbacks()
        }

        return DevicePreset(
            id = "current_device",
            brandLabel = "Current",
            modelLabel = "Device",
            summary = "Hardware values from this physical device",
            profile = profile
        )
    }

    fun getPresets(): List<DevicePreset> {
        val list = mutableListOf<DevicePreset>()
        list.add(createCurrentDevicePreset())
        
        // Hardcoded presets stolen from demo cave
        list.addAll(listOf(
            DevicePreset(
                "pixel_11_pro", "Google", "Pixel 11 Pro", "Tensor G6 - Android 17",
                DeviceProfile(
                    brand = "Google", manufacturer = "Google", model = "GM45K",
                    productName = "lynx", deviceCode = "lynx", board = "lynx",
                    hardware = "google", boardPlatform = "gs401",
                    buildRelease = "17", buildSdk = 37,
                    buildId = "UP1A.260805.001", securityPatch = "2026-08-05"
                ).apply { applyFallbacks() }
            ),
            DevicePreset(
                "galaxy_s26_ultra", "Samsung", "Galaxy S26 Ultra", "Snapdragon 9 Gen 1 - Android 16",
                DeviceProfile(
                    brand = "samsung", manufacturer = "samsung", model = "SM-S948B",
                    productName = "titan", deviceCode = "titan", board = "titan",
                    hardware = "qcom", boardPlatform = "titan",
                    buildRelease = "16", buildSdk = 36,
                    buildId = "AP4A.260105.001", securityPatch = "2026-01-01",
                    screenWidth = 1440, screenHeight = 3120, screenDensity = 500
                ).apply { applyFallbacks() }
            ),
            DevicePreset(
                "xiaomi_17_ultra", "Xiaomi", "17 Ultra", "Snapdragon 9 Gen 1 - Android 16",
                DeviceProfile(
                    brand = "Xiaomi", manufacturer = "Xiaomi", model = "25128PNA1G",
                    productName = "xuanyuan", deviceCode = "xuanyuan", board = "titan",
                    hardware = "qcom", boardPlatform = "titan",
                    buildRelease = "16", buildSdk = 36,
                    buildId = "AQ4A.260912.001", securityPatch = "2026-01-01",
                    screenWidth = 1440, screenHeight = 3200, screenDensity = 522
                ).apply { applyFallbacks() }
            ),
            DevicePreset(
                "oneplus_15", "OnePlus", "15", "Snapdragon 8 Gen 4 - Android 15",
                DeviceProfile(
                    brand = "OnePlus", manufacturer = "OnePlus", model = "CPH2747",
                    productName = "OnePlus15", deviceCode = "OnePlus15", board = "OnePlus15",
                    hardware = "qcom", boardPlatform = "sun",
                    buildRelease = "15", buildSdk = 35,
                    buildId = "UKQ1.240917.001", securityPatch = "2025-02-05"
                ).apply { applyFallbacks() }
            ),
            DevicePreset(
                "tab_s11_ultra", "Samsung", "Galaxy Tab S11 Ultra", "Snapdragon 9 Gen 1 - Android 16",
                DeviceProfile(
                    brand = "samsung", manufacturer = "samsung", model = "SM-X930",
                    productName = "gts11ultra", deviceCode = "gts11ultra", board = "titan",
                    hardware = "qcom", boardPlatform = "titan",
                    buildRelease = "16", buildSdk = 36,
                    buildId = "AP4A.260105.001", securityPatch = "2026-02-01",
                    buildCharacteristics = "tablet",
                    screenWidth = 1848, screenHeight = 2960, screenDensity = 320
                ).apply { applyFallbacks() }
            )
        ))
        return list
    }
    
    // Helper to generate the raw config string for ConfigProvider
    fun renderConfig(profile: DeviceProfile, globalProps: Map<String, String>): String {
        val sb = StringBuilder()
        sb.append("# Catsmoker generated profile\n\n")
        
        // Identity
        sb.append("ro.product.brand=${profile.brand}\n")
        sb.append("ro.product.manufacturer=${profile.manufacturer}\n")
        sb.append("ro.product.model=${profile.model}\n")
        sb.append("ro.product.name=${profile.productName}\n")
        sb.append("ro.product.device=${profile.deviceCode}\n")
        sb.append("ro.product.board=${profile.board}\n")
        sb.append("ro.hardware=${profile.hardware}\n")
        sb.append("ro.board.platform=${profile.boardPlatform}\n")
        
        // Build
        sb.append("ro.build.fingerprint=${profile.buildFingerprint}\n")
        sb.append("ro.build.id=${profile.buildId}\n")
        sb.append("ro.build.display.id=${profile.buildDisplayId}\n")
        sb.append("ro.build.version.incremental=${profile.buildIncremental}\n")
        sb.append("ro.build.version.release=${profile.buildRelease}\n")
        sb.append("ro.build.version.sdk=${profile.buildSdk}\n")
        sb.append("ro.build.version.security_patch=${profile.securityPatch}\n")
        
        // Screen
        sb.append("screen.width=${profile.screenWidth}\n")
        sb.append("screen.height=${profile.screenHeight}\n")
        sb.append("screen.density=${profile.screenDensity}\n")
        
        // Network
        sb.append("gsm.operator.alpha=${profile.operatorAlpha}\n")
        sb.append("gsm.operator.numeric=${profile.operatorNumeric}\n")
        sb.append("gsm.sim.operator.iso-country=${profile.simCountryIso}\n")
        sb.append("persist.sys.timezone=${profile.timezone}\n")
        sb.append("persist.sys.locale=${profile.locale}\n")
        
        // WebView
        sb.append("webview.user_agent=${profile.userAgent}\n")
        
        // IDs
        sb.append("ro.serialno=${profile.serialNumber}\n")
        sb.append("ro.bootloader=${profile.bootloader}\n")
        sb.append("ANDROID_ID=${profile.androidId}\n")
        sb.append("device.imei=${profile.imei}\n")
        sb.append("device.meid=${profile.meid}\n")
        sb.append("device.imsi=${profile.subscriberId}\n")
        sb.append("device.iccid=${profile.simSerialNumber}\n")
        sb.append("device.phone_number=${profile.phoneNumber}\n")
        sb.append("device.gaid=${profile.gaid}\n")
        sb.append("device.gsf_id=${profile.gsfId}\n")
        sb.append("device.media_drm_id=${profile.mediaDrmId}\n")
        sb.append("device.app_set_id=${profile.appSetId}\n")
        
        // Global/Extra
        if (globalProps.isNotEmpty()) {
            sb.append("\n# Global Settings\n")
            globalProps.forEach { (k, v) -> sb.append("$k=$v\n") }
        }
        
        return sb.toString()
    }
}
