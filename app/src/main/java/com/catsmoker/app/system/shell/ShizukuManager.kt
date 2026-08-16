package com.catsmoker.app.system.shell

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ShizukuManager @Inject constructor() {
    fun isAvailable(): Boolean = Shizuku.pingBinder()
    fun hasPermission(): Boolean = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    fun requestPermission(requestCode: Int) = Shizuku.requestPermission(requestCode)
}
