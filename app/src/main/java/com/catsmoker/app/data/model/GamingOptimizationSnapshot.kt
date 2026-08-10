package com.catsmoker.app.data.model

import org.json.JSONArray
import org.json.JSONObject

data class GamingOptimizationSnapshot(
    val activeGamePackage: String?,
    val activeGameUid: Int?,
    val timestamp: Long,
    val minRefreshRate: SettingValue?,
    val peakRefreshRate: SettingValue?,
    val touchResponseSpeed: SettingValue?,
    val userPreferredDisplayModeId: SettingValue?,
    val affectedPackages: Set<String>
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("activeGamePackage", activeGamePackage ?: JSONObject.NULL)
        json.put("activeGameUid", activeGameUid ?: JSONObject.NULL)
        json.put("timestamp", timestamp)
        json.put("minRefreshRate", minRefreshRate?.toJson() ?: JSONObject.NULL)
        json.put("peakRefreshRate", peakRefreshRate?.toJson() ?: JSONObject.NULL)
        json.put("touchResponseSpeed", touchResponseSpeed?.toJson() ?: JSONObject.NULL)
        json.put("userPreferredDisplayModeId", userPreferredDisplayModeId?.toJson() ?: JSONObject.NULL)
        
        val pkgsArray = JSONArray()
        affectedPackages.forEach { pkgsArray.put(it) }
        json.put("affectedPackages", pkgsArray)
        
        return json.toString()
    }
    
    companion object {
        fun fromJson(jsonStr: String): GamingOptimizationSnapshot? {
            return try {
                val json = JSONObject(jsonStr)
                val affectedPkgs = mutableSetOf<String>()
                if (json.has("affectedPackages")) {
                    val pkgsArray = json.getJSONArray("affectedPackages")
                    for (i in 0 until pkgsArray.length()) {
                        affectedPkgs.add(pkgsArray.getString(i))
                    }
                }
                
                GamingOptimizationSnapshot(
                    activeGamePackage = if (json.isNull("activeGamePackage")) null else json.getString("activeGamePackage"),
                    activeGameUid = if (json.isNull("activeGameUid")) null else json.getInt("activeGameUid"),
                    timestamp = json.getLong("timestamp"),
                    minRefreshRate = if (json.isNull("minRefreshRate")) null else SettingValue.fromJson(json.getJSONObject("minRefreshRate")),
                    peakRefreshRate = if (json.isNull("peakRefreshRate")) null else SettingValue.fromJson(json.getJSONObject("peakRefreshRate")),
                    touchResponseSpeed = if (json.isNull("touchResponseSpeed")) null else SettingValue.fromJson(json.getJSONObject("touchResponseSpeed")),
                    userPreferredDisplayModeId = if (json.isNull("userPreferredDisplayModeId")) null else SettingValue.fromJson(json.getJSONObject("userPreferredDisplayModeId")),
                    affectedPackages = affectedPkgs
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

data class SettingValue(
    val value: String,
    val existed: Boolean
) {
    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("value", value)
        json.put("existed", existed)
        return json
    }
    
    companion object {
        fun fromJson(json: JSONObject): SettingValue {
            return SettingValue(
                value = json.getString("value"),
                existed = json.getBoolean("existed")
            )
        }
        
        fun fromCommandOutput(output: String): SettingValue {
            val trimmed = output.trim()
            return when {
                trimmed.isEmpty() || trimmed == "null" -> SettingValue("", existed = false)
                else -> SettingValue(trimmed, existed = true)
            }
        }
    }
}
