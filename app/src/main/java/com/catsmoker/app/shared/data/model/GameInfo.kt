package com.catsmoker.app.shared.data.model

import android.graphics.drawable.Drawable

data class GameInfo(
    val appName: String,
    val packageName: String,
    val icon: Drawable,
    val playTime: String? = null
)
