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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.catsmoker.app.R
import com.catsmoker.app.shared.ui.components.ScreenScaffold

@Composable
fun LogsRoute(onBack: () -> Unit) {
    val viewModel: LogsViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsState()

    LogsScreen(
        logs = uiState.logs,
        isLoading = uiState.isLoading,
        filterQuery = uiState.filterQuery,
        onFilterQueryChanged = viewModel::onFilterQueryChanged,
        onRefresh = viewModel::refreshLogs,
        onClear = viewModel::clearLogs,
        onShare = viewModel::shareLogs,
        onBack = onBack
    )
}

@Composable
fun LogsScreen(
    logs: List<String>,
    isLoading: Boolean,
    filterQuery: String,
    onFilterQueryChanged: (String) -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onShare: () -> Unit,
    onBack: () -> Unit
) {
    val scrollState = rememberLazyListState()
    val filteredLogs = remember(logs, filterQuery) {
        if (filterQuery.isBlank()) logs
        else logs.filter { it.contains(filterQuery, ignoreCase = true) }
    }

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            scrollState.scrollToItem(logs.size - 1)
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.logs_title),
        subtitle = stringResource(R.string.logs_subtitle),
        onBack = onBack,
        trailingContent = {
            IconButton(onClick = onRefresh, enabled = !isLoading) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = Color.White)
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
            }
            IconButton(onClick = onClear) {
                Icon(Icons.Default.Delete, contentDescription = "Clear", tint = Color.White)
            }
        }
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            OutlinedTextField(
                value = filterQuery,
                onValueChange = onFilterQueryChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.logs_filter_placeholder), color = Color.Gray) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.2f)
                ),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                } else if (filteredLogs.isEmpty()) {
                    Text(
                        text = stringResource(R.string.logs_empty),
                        color = Color.Gray,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    LazyColumn(
                        state = scrollState,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(8.dp)
                    ) {
                        items(filteredLogs) { line ->
                            Text(
                                text = line,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = getLogColor(line),
                                modifier = Modifier.padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

fun getLogColor(line: String): Color {
    return when {
        line.contains(" E/") || line.contains("ERROR") -> Color(0xFFFF5252)
        line.contains(" W/") || line.contains("WARN") -> Color(0xFFFFD740)
        line.contains(" I/") || line.contains("INFO") -> Color(0xFF40C4FF)
        line.contains(" D/") || line.contains("DEBUG") -> Color(0xFFB0BEC5)
        else -> Color.White.copy(alpha = 0.7f)
    }
}
