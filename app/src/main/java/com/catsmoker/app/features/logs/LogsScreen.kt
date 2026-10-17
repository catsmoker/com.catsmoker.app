package com.catsmoker.app.features.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.catsmoker.app.R
import com.catsmoker.app.shared.ui.components.ScreenScaffold
import com.catsmoker.app.shared.ui.theme.LogTerminalBackground
import com.catsmoker.app.shared.ui.theme.logLineColor

/**
 * System Logs: the app's live log, nothing else.
 *
 * Collection runs at the process level ([AppLogCollector], started from the
 * Application): this screen only observes the buffer. Exactly one action menu
 * (Copy / Share / Clear) — no refresh button (new lines arrive on their own),
 * no filter, no recording, no diagnostics. Clearing empties the displayed
 * buffer after confirmation; collection continues.
 */
@Composable
fun LogsRoute(onBack: () -> Unit) {
    val viewModel: LogsViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()

    LogsScreen(
        logs = uiState.logs,
        collectionFailed = uiState.collectionFailed,
        onCopy = viewModel::copyLogs,
        onShare = viewModel::shareLogs,
        onClear = viewModel::clearLogs,
        onBack = onBack
    )
}

@Composable
fun LogsScreen(
    logs: List<String>,
    collectionFailed: Boolean,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit
) {
    val scrollState = rememberLazyListState()
    var showClearConfirm by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }

    // Follow-the-tail without yanking. Two cases, kept explicit because the obvious
    // one-liner is wrong: on first fill the layout still shows the top window, and
    // reading that as "user scrolled up" pins the list at the top forever (seen live:
    // a 200-line buffer that never left its first screen). So the first fill always
    // lands on the bottom; afterwards the list follows only while the user is already
    // at the bottom. The comparison anchors on the *previous* size because the
    // layout the effect reads can lag one batch behind the new list.
    var initialScrolled by remember { mutableStateOf(false) }
    var prevSize by remember { mutableIntStateOf(0) }
    // Keyed on the list itself, not its size: at the buffer cap every batch has the
    // same size and a size key would stop refiring (same freeze, quieter).
    LaunchedEffect(logs) {
        if (logs.isEmpty()) {
            prevSize = 0
            return@LaunchedEffect
        }
        val lastVisible = scrollState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
        if (!initialScrolled || lastVisible == null || lastVisible >= minOf(prevSize, logs.size) - 2) {
            scrollState.scrollToItem(logs.size - 1)
            initialScrolled = true
        }
        prevSize = logs.size
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.logs_clear_title)) },
            text = { Text(stringResource(R.string.logs_clear_body)) },
            confirmButton = {
                TextButton(onClick = { showClearConfirm = false; onClear() }) {
                    Text(
                        stringResource(R.string.logs_clear),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.sys_cancel))
                }
            }
        )
    }

    ScreenScaffold(
        title = stringResource(R.string.logs_title),
        subtitle = stringResource(R.string.logs_subtitle),
        onBack = onBack,
        trailingContent = {
            Box {
                IconButton(onClick = { overflowOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface)
                }
                DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.logs_copy)) },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        onClick = { overflowOpen = false; onCopy() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.logs_share)) },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = { overflowOpen = false; onShare() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.logs_clear)) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { overflowOpen = false; showClearConfirm = true }
                    )
                }
            }
        }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                collectionFailed -> {
                    Text(
                        text = stringResource(
                            R.string.core_logs_fetch_error,
                            stringResource(R.string.logs_collection_failed)
                        ),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                logs.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.logs_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                else -> {
                    LazyColumn(
                        state = scrollState,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(LogTerminalBackground, RoundedCornerShape(12.dp))
                            .padding(8.dp)
                    ) {
                        items(logs) { line ->
                            Text(
                                text = line,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = logLineColor(line),
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

