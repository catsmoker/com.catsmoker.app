package com.catsmoker.app.util

import android.app.Dialog
import androidx.appcompat.app.AlertDialog

fun Dialog.safeShow(tag: String? = null): Boolean {
    return try {
        this.show()
        true
    } catch (e: Exception) {
        false
    }
}

fun AlertDialog.safeDismiss(tag: String? = null): Boolean {
    return try {
        if (this.isShowing) {
            this.dismiss()
        }
        true
    } catch (e: Exception) {
        false
    }
}
