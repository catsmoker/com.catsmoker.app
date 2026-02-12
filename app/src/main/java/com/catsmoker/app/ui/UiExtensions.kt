package com.catsmoker.app.ui

import androidx.appcompat.app.AppCompatActivity
import android.widget.ImageButton
import android.widget.TextView
import com.catsmoker.app.R
import com.google.android.material.snackbar.Snackbar

fun AppCompatActivity.setupScreenHeader(
    titleRes: Int,
    subtitleRes: Int,
    showBackButton: Boolean = true
) {
    findViewById<TextView>(R.id.header_title)?.setText(titleRes)
    findViewById<TextView>(R.id.header_subtitle)?.setText(subtitleRes)
    val backButton = findViewById<ImageButton>(R.id.header_back_button)
    backButton?.visibility = if (showBackButton) android.view.View.VISIBLE else android.view.View.INVISIBLE
    backButton?.isEnabled = showBackButton
    if (showBackButton) {
        backButton?.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
    } else {
        backButton?.setOnClickListener(null)
    }
}

fun AppCompatActivity.showSnackbar(message: String) {
    Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_SHORT).show()
}

fun AppCompatActivity.showLongSnackbar(message: String) {
    Snackbar.make(findViewById(android.R.id.content), message, Snackbar.LENGTH_LONG).show()
}



