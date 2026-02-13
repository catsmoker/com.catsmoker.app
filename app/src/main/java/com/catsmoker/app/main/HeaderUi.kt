package com.catsmoker.app.main

import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.catsmoker.app.R

fun AppCompatActivity.setupScreenHeader(
    titleRes: Int,
    subtitleRes: Int,
    showBackButton: Boolean = true
) {
    findViewById<TextView>(R.id.header_title)?.setText(titleRes)
    findViewById<TextView>(R.id.header_subtitle)?.setText(subtitleRes)
    val backButton = findViewById<ImageButton>(R.id.header_back_button)
    backButton?.visibility = if (showBackButton) View.VISIBLE else View.INVISIBLE
    backButton?.isEnabled = showBackButton
    backButton?.setOnClickListener(
        if (showBackButton) {
            View.OnClickListener { onBackPressedDispatcher.onBackPressed() }
        } else {
            null
        }
    )
}


