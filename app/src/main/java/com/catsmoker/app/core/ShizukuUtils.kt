package com.catsmoker.app.core

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.catsmoker.app.BuildConfig
import rikka.shizuku.Shizuku

fun hasShizukuPermission(): Boolean {
    return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
}

fun requestShizukuPermissionIfNeeded(requestCode: Int): Boolean {
    if (hasShizukuPermission()) return true
    Shizuku.requestPermission(requestCode)
    return false
}

fun createShizukuServiceArgs(
    context: Context,
    processNameSuffix: String,
    version: Int = BuildConfig.VERSION_CODE
): Shizuku.UserServiceArgs {
    return Shizuku.UserServiceArgs(ComponentName(context, ShizukuCommandService::class.java))
        .daemon(false)
        .processNameSuffix(processNameSuffix)
        .debuggable(BuildConfig.DEBUG)
        .version(version)
}
