package com.catsmoker.app.shared.data.model

data class SettingValue(val value: String, val existed: Boolean) {
    companion object {
        fun fromCommandOutput(output: String): SettingValue {
            val trimmed = output.trim()
            return if (trimmed == "null" || trimmed.isEmpty()) {
                SettingValue("", false)
            } else {
                SettingValue(trimmed, true)
            }
        }
    }
}
