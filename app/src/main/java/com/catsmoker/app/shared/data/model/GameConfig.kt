package com.catsmoker.app.shared.data.model

data class GameConfig(
    val packageName: String,
    val saveDir: String,
    val saveFile: String,
    val maxFpsAssetPath: String? = null,
    val ipadViewAssetPath: String? = null
)
