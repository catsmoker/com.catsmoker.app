package com.catsmoker.app.shared.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class DialogState {
    var isShowing by mutableStateOf(false)
}

@Composable
fun rememberDialogState(): DialogState {
    return remember { DialogState() }
}
