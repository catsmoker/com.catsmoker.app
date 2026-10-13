package com.catsmoker.app.features.permissions

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.catsmoker.app.R
import com.catsmoker.app.shared.ui.components.CatsmokerButton
import com.catsmoker.app.shared.ui.components.LanguageOptions
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.components.SectionCard
import com.catsmoker.app.shared.ui.components.ThemeModeOptions
import com.catsmoker.app.shared.ui.theme.CatsmokerTheme
import com.catsmoker.app.system.config.AppearanceStore
import com.catsmoker.app.system.config.LocaleHelper

@Composable
fun PermissionRoute(onDone: () -> Unit) {
    val viewModel: PermissionViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshStates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // A newly picked language restarts the process — the ViewModel asks, the screen
    // obeys. The chosen flag survives it (committed prefs + init resume).
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            if (event == PermissionViewModel.UiEvent.RestartApp) {
                LocaleHelper.restartApp(context)
            }
        }
    }

    if (uiState.isAppearanceStep) {
        AppearanceStepScreen(
            themeMode = uiState.themeMode,
            languageTag = uiState.languageTag,
            onThemeChange = viewModel::onThemeModeChanged,
            onLanguageChange = viewModel::onLanguageChanged,
            onContinue = viewModel::onAppearanceContinue
        )
    } else if (uiState.isAgreementStep) {
        AgreementStepScreen(
            agreed = uiState.isAgreed,
            onAgreedChange = viewModel::onAgreedChange,
            onContinue = { viewModel.onAgreementContinue() }
        )
    } else {
        PermissionsListScreen(
            uiState = uiState,
            onRefresh = viewModel::refreshStates,
            onRequestRoot = viewModel::requestRootPermission,
            onRequestShizuku = viewModel::requestShizukuPermission,
            onDone = { viewModel.onDone(onDone) }
        )
    }
}

/**
 * Step one: theme + language. The step scrolls on short viewports and the two
 * pickers sit side by side only on wide screens — stacked on a narrow phone.
 * The theme previews live (the whole app recomposes around this screen); the
 * language stages until Continue, which recreates the activity when it changed.
 */
@Composable
fun AppearanceStepScreen(
    themeMode: AppearanceStore.ThemeMode,
    languageTag: String,
    onThemeChange: (AppearanceStore.ThemeMode) -> Unit,
    onLanguageChange: (String) -> Unit,
    onContinue: () -> Unit
) {
    // Radio rows stay tap-friendly (~36.dp) without the 48.dp touch-target expansion
    // that would push the second card off-screen. Onboarding only — Settings keeps the
    // comfortable spacing.
    // The whole step scrolls and the two pickers stack on narrow phones: side by side
    // they each got ~128.dp and long language names wrapped to 3 lines or clipped.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 24.dp) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val narrow = maxWidth < 480.dp
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    stringResource(R.string.sys_first_run_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    stringResource(R.string.sys_first_run_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))

                SectionCard {
                    if (narrow) {
                        Text(
                            stringResource(R.string.sys_theme_title),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        ThemeModeOptions(selected = themeMode, onSelect = onThemeChange)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.sys_language_title),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LanguageOptions(selectedTag = languageTag, onSelect = onLanguageChange)
                    } else {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.sys_theme_title),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                ThemeModeOptions(selected = themeMode, onSelect = onThemeChange)
                            }
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.sys_language_title),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                LanguageOptions(selectedTag = languageTag, onSelect = onLanguageChange)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                CatsmokerButton(
                    onClick = onContinue,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(stringResource(R.string.sys_continue), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Step two: the terms checkbox gates its own Continue — one centered screen, no scrolling. */
@Composable
fun AgreementStepScreen(
    agreed: Boolean,
    onAgreedChange: (Boolean) -> Unit,
    onContinue: () -> Unit
) {
    // The linked license text names the open-source license (not the app terms): the full
    // Privacy Policy and Terms of Service open as dialogs below, from the same string
    // resources the About/Settings legal screens render.
    var showPrivacy by remember { mutableStateOf(false) }
    var showTerms by remember { mutableStateOf(false) }
    val annotatedLinkString = buildAnnotatedString {
        append(stringResource(R.string.core_terms_prefix))
        withLink(
            LinkAnnotation.Url(
                url = "https://creativecommons.org/licenses/by-nc-sa/4.0/",
                styles = TextLinkStyles(style = SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
            )
        ) {
            append(stringResource(R.string.core_terms_link))
        }
        append(stringResource(R.string.core_terms_suffix))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(R.string.core_terms_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = annotatedLinkString,
            style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { showPrivacy = true }) {
                Text(stringResource(R.string.core_terms_read_privacy))
            }
            TextButton(onClick = { showTerms = true }) {
                Text(stringResource(R.string.core_terms_read_terms))
            }
        }
        // Website legal page, reachable before any consent is given. A plain
        // browser intent: opening it never flips `agreed` or records anything.
        val uriHandler = LocalUriHandler.current
        val legalUrl = stringResource(R.string.url_legal)
        TextButton(
            onClick = { uriHandler.openUri(legalUrl) }
        ) {
            Text(stringResource(R.string.legal_website_title))
        }

        Spacer(modifier = Modifier.height(24.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable { onAgreedChange(!agreed) }
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            Checkbox(
                checked = agreed,
                onCheckedChange = null,
                colors = CheckboxDefaults.colors(
                    uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    checkedColor = MaterialTheme.colorScheme.primary
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.core_terms_agree), color = MaterialTheme.colorScheme.onSurface)
        }
        Spacer(modifier = Modifier.height(32.dp))
        CatsmokerButton(
            onClick = onContinue,
            enabled = agreed,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(stringResource(R.string.core_continue), fontWeight = FontWeight.Bold)
        }
    }

    if (showPrivacy) {
        com.catsmoker.app.features.about.LegalDialog(
            title = stringResource(R.string.legal_privacy_title),
            intro = stringResource(R.string.legal_p_intro),
            sections = listOf(
                stringResource(R.string.legal_p_local_title) to stringResource(R.string.legal_p_local_body),
                stringResource(R.string.legal_p_ads_title) to stringResource(R.string.legal_p_ads_body),
                stringResource(R.string.legal_p_updates_title) to stringResource(R.string.legal_p_updates_body),
                stringResource(R.string.legal_p_sharing_title) to stringResource(R.string.legal_p_sharing_body),
                stringResource(R.string.legal_p_external_title) to stringResource(R.string.legal_p_external_body),
                stringResource(R.string.legal_p_permissions_title) to stringResource(R.string.legal_p_permissions_body),
                stringResource(R.string.legal_p_vpn_title) to stringResource(R.string.legal_p_vpn_body),
                stringResource(R.string.legal_p_contact_title) to stringResource(R.string.legal_p_contact_body)
            ),
            dismissLabel = stringResource(R.string.sys_later),
            onDismiss = { showPrivacy = false }
        )
    }

    if (showTerms) {
        com.catsmoker.app.features.about.LegalDialog(
            title = stringResource(R.string.legal_terms_title),
            intro = stringResource(R.string.legal_t_intro),
            sections = listOf(
                stringResource(R.string.legal_t_license_title) to stringResource(R.string.legal_t_license_body),
                stringResource(R.string.legal_t_games_title) to stringResource(R.string.legal_t_games_body),
                stringResource(R.string.legal_t_risk_title) to stringResource(R.string.legal_t_risk_body),
                stringResource(R.string.legal_t_donations_title) to stringResource(R.string.legal_t_donations_body),
                stringResource(R.string.legal_t_use_title) to stringResource(R.string.legal_t_use_body),
                stringResource(R.string.legal_t_changes_title) to stringResource(R.string.legal_t_changes_body)
            ),
            dismissLabel = stringResource(R.string.sys_later),
            onDismiss = { showTerms = false }
        )
    }
}

/**
 * Step three: every permission in one grouped card with compact rows, sized to fit one
 * screen without scrolling. Reopened from Settings after onboarding, this is the only
 * step that shows. Granting jumps to system Settings; back returns here.
 */
// Pending Play declaration for REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (PLAYSTORE.md REVIEW) — kept intentionally.
@Composable
fun PermissionsListScreen(
    uiState: PermissionViewModel.UiState,
    onRefresh: () -> Unit,
    onRequestRoot: () -> Unit,
    onRequestShizuku: () -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { onRefresh() }

    val storageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { onRefresh() }

    val micLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { onRefresh() }

    val bluetoothLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { onRefresh() }

    ScreenScaffold(
        title = stringResource(R.string.core_permissions_title),
        subtitle = stringResource(R.string.core_permissions_subtitle),
        trailingContent = {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.logs_refresh), tint = MaterialTheme.colorScheme.primary)
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Nine rows plus DONE never fit a short phone viewport — the list scrolls
            // so the grant buttons stay reachable instead of pushed off-screen.
            // Compact touch targets again: rows stay short inside the scroll.
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 24.dp) {
                SectionCard {
                    PermissionRow(stringResource(R.string.core_perm_root_title), stringResource(R.string.core_perm_root_sub), uiState.rootGranted) { onRequestRoot() }
                    PermissionRow(stringResource(R.string.core_perm_shizuku_title), stringResource(R.string.core_perm_shizuku_sub), uiState.shizukuGranted) { onRequestShizuku() }
                    PermissionRow(stringResource(R.string.core_perm_storage_title), stringResource(R.string.core_perm_storage_sub), uiState.storageGranted) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            try {
                                context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                                    data = "package:${context.packageName}".toUri()
                                })
                            } catch (_: Exception) {
                                try {
                                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                                } catch (_: Exception) {
                                    context.startActivity(Intent(Settings.ACTION_SETTINGS))
                                }
                            }
                        } else {
                            storageLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        }
                    }
                    PermissionRow(stringResource(R.string.core_perm_battery_title), stringResource(R.string.core_perm_battery_sub), uiState.batteryGranted) {
                        try {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = "package:${context.packageName}".toUri() })
                        } catch (e: Exception) {
                            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                        }
                    }
                    PermissionRow(
                        stringResource(R.string.core_perm_notif_title),
                        stringResource(R.string.core_perm_notif_sub),
                        uiState.notifGranted,
                        // POST_NOTIFICATIONS only exists on Android 13+. Older phones keep the
                        // row visible but disabled with the reason, instead of a dead button.
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                        note = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) null
                        else stringResource(R.string.core_perm_need_13)
                    ) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                    PermissionRow(stringResource(R.string.core_perm_overlay_title), stringResource(R.string.core_perm_overlay_sub), uiState.overlayGranted) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri()))
                    }
                    PermissionRow(stringResource(R.string.core_perm_usage_title), stringResource(R.string.core_perm_usage_sub), uiState.usageGranted) {
                        context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                    }
                    PermissionRow(stringResource(R.string.core_perm_mic_title), stringResource(R.string.core_perm_mic_sub), uiState.micGranted) {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    PermissionRow(
                        stringResource(R.string.core_perm_bt_title),
                        stringResource(R.string.core_perm_bt_sub),
                        uiState.bluetoothGranted,
                        // BLUETOOTH_CONNECT only exists on Android 12+. Older phones keep the
                        // row visible but disabled with the reason, instead of a dead button.
                        enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                        note = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) null
                        else stringResource(R.string.core_perm_need_12)
                    ) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            bluetoothLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                CatsmokerButton(
                    onClick = onDone,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp)
                ) { Text(stringResource(R.string.core_done), fontWeight = FontWeight.Bold) }
            }
        }
    }
}

/** One permission line inside the grouped card: title + tiny subtitle + status/grant. */
@Composable
private fun PermissionRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    enabled: Boolean = true,
    /** Why this row cannot be granted here (Android version). Shown instead of hiding the row. */
    note: String? = null,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = if (enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            // A permission that does not exist on this Android version never shows
            // a check: the row stays visible but disabled with the reason instead.
            if (granted && enabled) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = title,
                tint = Color.Green,
                modifier = Modifier.size(20.dp)
            )
        } else {
                CatsmokerButton(
                    onClick = onClick,
                    enabled = enabled,
                    modifier = Modifier.height(30.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp)
                ) {
                    Text(stringResource(R.string.core_grant), fontSize = 11.sp)
                }
            }
        }
        if (!enabled && note != null) {
            Text(
                text = note,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 14.sp,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun AppearanceStepPreview() {
    CatsmokerTheme {
        AppearanceStepScreen(
            themeMode = AppearanceStore.ThemeMode.SYSTEM,
            languageTag = AppearanceStore.LANGUAGE_SYSTEM,
            onThemeChange = {},
            onLanguageChange = {},
            onContinue = {}
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun AgreementStepPreview() {
    CatsmokerTheme {
        AgreementStepScreen(agreed = true, onAgreedChange = {}, onContinue = {})
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF000000)
@Composable
fun PermissionsPreview() {
    CatsmokerTheme {
        PermissionsListScreen(
            uiState = PermissionViewModel.UiState(
                isAppearanceStep = false,
                isAgreementStep = false,
                rootGranted = true,
                notifGranted = false,
                overlayGranted = true
            ),
            onRefresh = {},
            onRequestRoot = {},
            onRequestShizuku = {},
            onDone = {}
        )
    }
}
