package com.catsmoker.app.shared.ui.components

import android.view.View
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.startapp.sdk.ads.banner.Banner
import com.startapp.sdk.ads.banner.BannerListener

@Composable
fun StartAppBanner(modifier: Modifier = Modifier) {
    val configuration = LocalConfiguration.current
    val bannerHeightDp = if (configuration.screenWidthDp >= 600) 90 else 50

    // The slot is reserved up front so a successful ad never shifts content, but it has to
    // collapse when no ad arrives (blocking DNS, offline, no fill) or the gap stays forever.
    val loadFailed = rememberSaveable { mutableStateOf(false) }
    if (loadFailed.value) return

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val width = maxWidth.value.toInt()

        // Use key to recreate the banner if width or height changes
        key(width, bannerHeightDp) {
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(bannerHeightDp.dp),
                factory = { context ->
                    val listener = object : BannerListener {
                        override fun onReceiveAd(view: View?) {}
                        override fun onFailedToReceiveAd(view: View?) {
                            loadFailed.value = true
                        }
                        override fun onImpression(view: View?) {}
                        override fun onClick(view: View?) {}
                    }
                    Banner(context, listener).apply {
                        loadAd(width, bannerHeightDp)
                    }
                }
            )
        }
    }
}
