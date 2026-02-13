package com.catsmoker.app

import android.util.Log
import androidx.appcompat.app.AlertDialog

fun AlertDialog.safeShow(logTag: String): Boolean {
    return try {
        show()
        true
    } catch (e: SecurityException) {
        Log.w(logTag, "Dialog show blocked by system permission policy", e)
        false
    }
}

fun AlertDialog.safeDismiss(logTag: String): Boolean {
    return try {
        dismiss()
        true
    } catch (e: SecurityException) {
        Log.w(logTag, "Dialog dismiss blocked by system permission policy", e)
        false
    }
}
