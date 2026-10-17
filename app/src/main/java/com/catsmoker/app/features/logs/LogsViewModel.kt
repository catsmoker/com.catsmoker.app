package com.catsmoker.app.features.logs

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catsmoker.app.R
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * System Logs: a thin observer over the app-level collector.
 *
 * Collection itself lives in [AppLogCollector] (started once from the Application,
 * not from this screen): this ViewModel only forwards the live tail plus the
 * copy/share/clear actions, all operating on the in-memory buffer. Clearing
 * empties the buffer — collection continues, new lines keep arriving.
 */
@HiltViewModel
class LogsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val collector: AppLogCollector
) : ViewModel() {

    data class LogsUiState(
        val logs: List<String> = emptyList(),
        val collectionFailed: Boolean = false
    )

    private val _uiState = MutableStateFlow(LogsUiState())
    val uiState: StateFlow<LogsUiState> = _uiState.asStateFlow()

    init {
        // Safety net: the Application starts the collector on process start, so this
        // is normally a no-op (idempotent). Off the main thread — first start blocks
        // ~200 ms on the reader's start grace.
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { collector.ensureStarted() }
        }
        viewModelScope.launch {
            collector.logs.collect { lines ->
                _uiState.update { it.copy(logs = lines) }
            }
        }
        viewModelScope.launch {
            collector.collectionFailed.collect { failed ->
                _uiState.update { it.copy(collectionFailed = failed) }
            }
        }
    }

    /**
     * Copies the buffered lines to the clipboard. An empty buffer reports instead
     * of copying nothing.
     */
    fun copyLogs() {
        val text = _uiState.value.logs.joinToString("\n")
        viewModelScope.launch(Dispatchers.Main) {
            runCatching {
                if (text.isBlank()) {
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.logs_empty),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                val clipboard =
                    context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("catsmoker-logs", text))
                android.widget.Toast.makeText(
                    context,
                    context.getString(R.string.logs_copied),
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /**
     * Clears the displayed buffer after the screen's confirmation. Buffer-only:
     * the system log, files, settings and game data are untouched, and the
     * collector keeps running, so new lines arrive normally afterwards.
     */
    fun clearLogs() {
        collector.clear()
    }

    /**
     * Shares the buffered lines through the FileProvider.
     *
     * The previous version put the whole dump in `EXTRA_TEXT`. A large buffer is
     * megabytes, and anything near a megabyte in an Intent extra dies in
     * `TransactionTooLargeException` — a fatal crash on the main thread. Only a
     * URI string crosses the binder now, so the buffer can be any size. Old shared
     * files are pruned to the newest few so the cache does not grow one file per
     * tap. An empty buffer reports instead of sharing an empty document.
     */
    fun shareLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_uiState.value.logs.isEmpty()) {
                withContext(Dispatchers.Main) {
                    runCatching {
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.logs_empty),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                return@launch
            }
            try {
                val dir = java.io.File(context.cacheDir, SHARE_DIR).apply { mkdirs() }
                dir.listFiles()
                    ?.sortedByDescending { it.lastModified() }
                    ?.drop(MAX_KEPT_FILES - 1)
                    ?.forEach { runCatching { it.delete() } }
                val file = java.io.File(
                    dir,
                    "catsmoker-logs-${java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())}.txt"
                )
                file.bufferedWriter().use { writer ->
                    _uiState.value.logs.forEach { line ->
                        writer.write(line)
                        writer.newLine()
                    }
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.core_logs_share_title))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                withContext(Dispatchers.Main) {
                    runCatching {
                        context.startActivity(
                            Intent.createChooser(intent, context.getString(R.string.core_logs_share_title)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        )
                    }.onFailure { e ->
                        // No share targets installed: report, don't crash. The file stays in
                        // cache for the next attempt rather than being rebuilt.
                        Log.w(TAG, "No app to share logs with", e)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not write logs for sharing", e)
            }
        }
    }

    private companion object {
        const val TAG = "LogsViewModel"

        /** Cache subdir holding shared log files (served by the FileProvider `cache-path`). */
        const val SHARE_DIR = "shared"

        /** Old shared files kept; the newest write makes room before writing. */
        const val MAX_KEPT_FILES = 3
    }
}
