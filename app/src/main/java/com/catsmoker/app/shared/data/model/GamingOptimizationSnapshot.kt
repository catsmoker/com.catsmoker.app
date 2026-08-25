package com.catsmoker.app.shared.data.model

import com.google.gson.Gson

data class GamingOptimizationSnapshot(
    val activeGamePackage: String?,
    val activeGameUid: Int?,
    val timestamp: Long,
    val minRefreshRate: SettingValue?,
    val peakRefreshRate: SettingValue?,
    val touchResponseSpeed: SettingValue?,
    val userPreferredDisplayModeId: SettingValue?,
    val affectedPackages: Set<String>,
    val uidWhitelistedBefore: Boolean = false,
    val vivoGameCubeApps: String? = null,
    val vivoSpeedModeApps: String? = null,
    val originalRingtoneVolume: Int? = null,
    val originalBrightnessMode: Int? = null,
    val originalRotation: Int? = null
) {
    fun toJson(): String = Gson().toJson(this)
    
    companion object {
        fun fromJson(json: String): GamingOptimizationSnapshot? {
            return try { Gson().fromJson(json, GamingOptimizationSnapshot::class.java) } catch (_: Exception) { null }
        }
    }
}
