package com.catsmoker.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catsmoker.app.BuildConfig
import com.catsmoker.app.R
import com.catsmoker.app.ui.components.InfoCard
import com.catsmoker.app.ui.components.QuickActionButton
import com.catsmoker.app.ui.components.ScreenScaffold
import com.catsmoker.app.ui.components.SectionCard
import com.catsmoker.app.ui.theme.CatsmokerTheme

@Composable
fun AboutScreen(
    adsEnabled: Boolean,
    autoCheck: Boolean,
    isPreRelease: Boolean,
    isUpdating: Boolean,
    updateProgress: Float,
    onAdsToggled: (Boolean) -> Unit,
    onAutoCheckToggled: (Boolean) -> Unit,
    onBuildTypeChanged: (Boolean) -> Unit,
    onOpenPermissions: () -> Unit,
    onBack: () -> Unit,
    onCheckUpdates: () -> Unit,
    onOpenUrl: (String) -> Unit
) {
    var adsLocal by remember { mutableStateOf(adsEnabled) }
    var autoCheckLocal by remember { mutableStateOf(autoCheck) }
    var isPreReleaseLocal by remember { mutableStateOf(isPreRelease) }

    ScreenScaffold(
        title = stringResource(R.string.about_header_title),
        subtitle = stringResource(R.string.about_header_subtitle),
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    modifier = Modifier.size(80.dp).clip(RoundedCornerShape(20.dp)),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Image(painter = painterResource(R.drawable.icon), contentDescription = "Logo", modifier = Modifier.padding(12.dp))
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = "Catsmoker", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(text = "v${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text("SETTINGS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            SectionCard {
                SettingsToggle("Personalized Ads", adsLocal) { adsLocal = it; onAdsToggled(it) }
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                
                QuickActionButton(
                    title = "App Permissions",
                    subtitle = "Manage required system access.",
                    iconContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    iconContentColor = MaterialTheme.colorScheme.primary,
                    onClick = onOpenPermissions,
                    isFullWidth = true,
                    showChevron = true,
                    icon = { Icon(Icons.Default.Security, null) }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text("UPDATE STATUS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            SectionCard {
                if (isUpdating) {
                    Column {
                        Text("Downloading update...", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(progress = { updateProgress }, modifier = Modifier.fillMaxWidth())
                        Text("${(updateProgress * 100).toInt()}%", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.End))
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Branch:", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
                        Spacer(modifier = Modifier.width(8.dp))
                        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BuildTypeButton("STABLE", !isPreReleaseLocal, Modifier.weight(1f)) { isPreReleaseLocal = false; onBuildTypeChanged(false) }
                            BuildTypeButton("DEV", isPreReleaseLocal, Modifier.weight(1f)) { isPreReleaseLocal = true; onBuildTypeChanged(true) }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = onCheckUpdates, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp)) {
                        Text("CHECK FOR UPDATES", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    SettingsToggle("Auto Check at Start", autoCheckLocal) { autoCheckLocal = it; onAutoCheckToggled(it) }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text("COMMUNITY & LEGAL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AboutLinkRow("Telegram", "Join the community", Icons.AutoMirrored.Filled.Send, Color(0xFF26A5E4), onOpenUrl)
                AboutLinkRow("YouTube", "Video guides", Icons.Default.PlayArrow, Color(0xFFFF0000), onOpenUrl)
                AboutLinkRow("GitHub", "Source code", Icons.Default.Code, MaterialTheme.colorScheme.onSurface, onOpenUrl)
                AboutLinkRow("Privacy Policy", "Legal info", Icons.Default.PrivacyTip, Color.Gray, onOpenUrl)
                
                Spacer(modifier = Modifier.height(8.dp))
                
                QuickActionButton(
                    title = "Donate",
                    subtitle = "Support development",
                    iconContainerColor = Color(0xFFFFD700).copy(alpha = 0.15f),
                    iconContentColor = Color(0xFFFFD700),
                    onClick = { onOpenUrl("https://catsmoker.vercel.app/#donate-section") },
                    isFullWidth = true,
                    showChevron = true,
                    icon = { Icon(Icons.Default.Star, null) }
                )
            }
        }
    }
}

@Composable
fun BuildTypeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.height(32.dp).clip(RoundedCornerShape(6.dp)).clickable { onClick() },
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold, fontSize = 10.sp)
        }
    }
}

@Composable
fun SettingsToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Medium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
fun AboutLinkRow(title: String, subtitle: String, icon: ImageVector, color: Color, onOpenUrl: (String) -> Unit) {
    val url = when(title) {
        "Telegram" -> "https://t.me/CATSM0KER"
        "YouTube" -> "https://www.youtube.com/@CATSMOKER"
        "GitHub" -> "https://github.com/catsmoker/com.catsmoker.app"
        "Privacy Policy" -> "https://www.freeprivacypolicy.com/live/36fce55a-e1f4-456c-a828-1b058664698a"
        else -> ""
    }
    QuickActionButton(
        title = title,
        subtitle = subtitle,
        iconContainerColor = color.copy(alpha = 0.1f),
        iconContentColor = color,
        onClick = { onOpenUrl(url) },
        isFullWidth = true,
        showChevron = true,
        icon = { Icon(icon, null) }
    )
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun AboutPreview() {
    CatsmokerTheme {
        AboutScreen(true, false, false, false, 0f, {}, {}, {}, {}, {}, {}, {})
    }
}
