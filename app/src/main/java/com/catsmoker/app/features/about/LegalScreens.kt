package com.catsmoker.app.features.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.catsmoker.app.R
import com.catsmoker.app.shared.ui.components.ScreenScaffold

/**
 * In-app legal pages.
 *
 * Both screens render only string resources (translated in all four locales) and describe
 * verified behavior only: local-only storage, Start.io ads with an in-app off switch, opt-in
 * GitHub update checks, user-initiated log sharing, and external donation processors. No legal
 * entities, addresses, or providers are invented — anything not known from the codebase is
 * left out rather than fabricated.
 */

@Composable
fun PrivacyPolicyRoute(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val legalUrl = stringResource(R.string.url_legal)
    ScreenScaffold(
        title = stringResource(R.string.legal_privacy_title),
        subtitle = stringResource(R.string.legal_privacy_subtitle),
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.legal_privacy_updated),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.legal_p_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            LegalSection(
                title = stringResource(R.string.legal_p_local_title),
                body = stringResource(R.string.legal_p_local_body)
            )
            LegalSection(
                title = stringResource(R.string.legal_p_ads_title),
                body = stringResource(R.string.legal_p_ads_body)
            )
            LegalSection(
                title = stringResource(R.string.legal_p_updates_title),
                body = stringResource(R.string.legal_p_updates_body)
            )
            LegalSection(
                title = stringResource(R.string.legal_p_sharing_title),
                body = stringResource(R.string.legal_p_sharing_body)
            )
            LegalSection(
                title = stringResource(R.string.legal_p_external_title),
                body = stringResource(R.string.legal_p_external_body)
            )
            LegalSection(
                title = stringResource(R.string.legal_p_permissions_title),
                body = stringResource(R.string.legal_p_permissions_body)
            )
            LegalSection(
                title = stringResource(R.string.legal_p_vpn_title),
                body = stringResource(R.string.legal_p_vpn_body)
            )
            LegalSection(
                title = stringResource(R.string.legal_p_contact_title),
                body = stringResource(R.string.legal_p_contact_body)
            )
            WebsiteLegalFooter(uriHandler = uriHandler, legalUrl = legalUrl)
        }
    }
}

@Composable
fun TermsRoute(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val legalUrl = stringResource(R.string.url_legal)
    ScreenScaffold(
        title = stringResource(R.string.legal_terms_title),
        subtitle = stringResource(R.string.legal_terms_subtitle),
        onBack = onBack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.legal_terms_updated),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.legal_t_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            LegalSection(
                title = stringResource(R.string.legal_t_license_title),
                body = stringResource(R.string.legal_t_license_body)
            )
            LegalSection(
                title = stringResource(R.string.legal_t_games_title),
                body = stringResource(R.string.legal_t_games_body)
            )
            LegalSection(
                title = stringResource(R.string.legal_t_risk_title),
                body = stringResource(R.string.legal_t_risk_body)
            )
            LegalSection(
                title = stringResource(R.string.legal_t_donations_title),
                body = stringResource(R.string.legal_t_donations_body)
            )
            LegalSection(
                title = stringResource(R.string.legal_t_use_title),
                body = stringResource(R.string.legal_t_use_body)
            )
            LegalSection(
                title = stringResource(R.string.legal_t_changes_title),
                body = stringResource(R.string.legal_t_changes_body)
            )
            WebsiteLegalFooter(uriHandler = uriHandler, legalUrl = legalUrl)
        }
    }
}

/**
 * Shared footer linking the website legal page from both in-app legal
 * screens. Opening it is a plain browser intent — it never touches consent.
 */
@Composable
private fun WebsiteLegalFooter(uriHandler: UriHandler, legalUrl: String) {
    Spacer(modifier = Modifier.height(20.dp))
    TextButton(onClick = { uriHandler.openUri(legalUrl) }) {
        Text(
            stringResource(R.string.legal_website_title),
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/**
 * One headed section of a legal page. Headings use [MaterialTheme.typography.titleSmall]
 * so screen readers and large-text scaling treat them as structure, not decoration.
 */
@Composable
private fun LegalSection(title: String, body: String) {    Spacer(modifier = Modifier.height(20.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * Scrollable dialog rendering the same string resources as the full screens, for surfaces
 * without navigation (the first-run agreement step). Dismisses via its button or tap-outside.
 */
@Composable
fun LegalDialog(
    title: String,
    intro: String,
    sections: List<Pair<String, String>>,
    dismissLabel: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = MaterialTheme.colorScheme.onSurface) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = intro,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                sections.forEach { (sectionTitle, sectionBody) ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = sectionTitle,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = sectionBody,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissLabel, color = MaterialTheme.colorScheme.primary)
            }
        }
    )
}
