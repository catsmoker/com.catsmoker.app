package com.catsmoker.app

import androidx.appcompat.app.AppCompatActivity
import android.widget.ImageButton
import android.widget.TextView

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
