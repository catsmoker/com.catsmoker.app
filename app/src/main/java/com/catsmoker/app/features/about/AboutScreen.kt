package com.catsmoker.app.features.about

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catsmoker.app.BuildConfig
import com.catsmoker.app.R
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.components.SectionCard
import com.catsmoker.app.shared.ui.theme.CatsmokerTheme

@Composable
fun AboutRoute(
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    onDonate: () -> Unit,
    onPrivacy: () -> Unit,
    onTerms: () -> Unit
) {
    AboutScreen(onBack = onBack, onOpenLogs = onOpenLogs, onDonate = onDonate, onPrivacy = onPrivacy, onTerms = onTerms)
}

@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenLogs: () -> Unit,
    onDonate: () -> Unit,
    onPrivacy: () -> Unit,
    onTerms: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val githubUrl = stringResource(R.string.url_github)
    val legalUrl = stringResource(R.string.url_legal)

    ScreenScaffold(title = stringResource(R.string.about_header_title), subtitle = stringResource(R.string.core_about_subtitle), onBack = onBack) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            // Header — compact paddings: the page still scrolls on short phones
            // (content is ~850.dp), but no empty travel on tall ones.
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(modifier = Modifier.size(80.dp).clip(RoundedCornerShape(20.dp)), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Icon(Icons.Default.Info, null, modifier = Modifier.padding(12.dp))
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(text = stringResource(R.string.app_name), fontWeight = FontWeight.Bold, fontSize = 20.sp, color = MaterialTheme.colorScheme.onSurface)
                Text(text = "v${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                
                Spacer(modifier = Modifier.height(16.dp))

                // Community links: full-width labeled buttons. Visible at a glance
                // and announced with their title by TalkBack — the icon-only row
                // they replace carried no content descriptions at all.
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val webUrl = stringResource(R.string.url_webpage)
                    val discordUrl = stringResource(R.string.url_discord)
                    val telegramUrl = stringResource(R.string.url_telegram)

                    AboutLinkButton(
                        title = stringResource(R.string.about_social_github),
                        painter = painterResource(R.drawable.ic_github),
                        onClick = { uriHandler.openUri(githubUrl) }
                    )
                    AboutLinkButton(
                        title = stringResource(R.string.about_social_website),
                        imageVector = Icons.Default.Language,
                        iconTint = MaterialTheme.colorScheme.primary,
                        onClick = { uriHandler.openUri(webUrl) }
                    )
                    AboutLinkButton(
                        title = stringResource(R.string.about_social_discord),
                        painter = painterResource(R.drawable.ic_discord),
                        iconTint = Color(0xFF5865F2),
                        onClick = { uriHandler.openUri(discordUrl) }
                    )
                    AboutLinkButton(
                        title = stringResource(R.string.about_social_telegram),
                        painter = painterResource(R.drawable.ic_telegram),
                        iconTint = Color(0xFF229ED9),
                        onClick = { uriHandler.openUri(telegramUrl) }
                    )
                    AboutLinkButton(
                        title = stringResource(R.string.donate_title),
                        imageVector = Icons.Default.Favorite,
                        iconTint = MaterialTheme.colorScheme.primary,
                        external = false,
                        onClick = onDonate
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(stringResource(R.string.about_header_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.core_about_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Construction,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.core_about_dev_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.core_about_dev_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = onOpenLogs, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.core_about_dev_logs))
                    }
                    Button(
                        onClick = { uriHandler.openUri("$githubUrl/issues") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.core_about_dev_report))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.legal_section_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AboutLinkButton(
                    title = stringResource(R.string.legal_privacy_title),
                    imageVector = Icons.Default.Policy,
                    iconTint = MaterialTheme.colorScheme.primary,
                    external = false,
                    onClick = onPrivacy
                )
                AboutLinkButton(
                    title = stringResource(R.string.legal_terms_title),
                    imageVector = Icons.Default.Description,
                    iconTint = MaterialTheme.colorScheme.primary,
                    external = false,
                    onClick = onTerms
                )
                AboutLinkButton(
                    title = stringResource(R.string.legal_website_title),
                    imageVector = Icons.Default.Language,
                    iconTint = MaterialTheme.colorScheme.primary,
                    external = true,
                    onClick = { uriHandler.openUri(legalUrl) }
                )
            }
        }
    }
}

@Composable
fun AboutLinkButton(
    title: String,
    onClick: () -> Unit,
    painter: Painter? = null,
    imageVector: ImageVector? = null,
    iconTint: Color = MaterialTheme.colorScheme.onSurface,
    /** True for browser links (trailing open-in-new); false for in-app destinations (chevron). */
    external: Boolean = true
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        if (painter != null) {
            Icon(
                painter = painter,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        } else if (imageVector != null) {
            Icon(
                imageVector = imageVector,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall
        )
        Icon(
            imageVector = if (external) Icons.AutoMirrored.Filled.OpenInNew else Icons.Default.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun AboutPreview() {
    CatsmokerTheme {
        AboutScreen(onBack = {}, onOpenLogs = {}, onDonate = {}, onPrivacy = {}, onTerms = {})
    }
}
