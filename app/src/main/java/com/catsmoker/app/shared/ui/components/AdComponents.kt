package com.catsmoker.app.shared.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.startapp.sdk.ads.banner.Banner

@Composable
fun StartAppBanner(modifier: Modifier = Modifier) {
    val configuration = LocalConfiguration.current
    val bannerHeightDp = if (configuration.screenWidthDp >= 600) 90 else 50

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val width = maxWidth.value.toInt()
        
        // Use key to recreate the banner if width or height changes
        key(width, bannerHeightDp) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bannerHeightDp.dp),
                factory = { context ->
                    Banner(context).apply {
                        loadAd(width, bannerHeightDp)
                    }
                }
            )
        }
    }
}
