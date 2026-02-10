package com.catsmoker.app.services

import android.graphics.drawable.Drawable

data class GameInfo(
    val appName: String?,
    val packageName: String?,
    val icon: Drawable?,
    val playTime: String?
)