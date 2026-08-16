package com.catsmoker.app.features.editgamefiles

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.catsmoker.app.R
import com.catsmoker.app.shared.data.model.GameConfig
import com.catsmoker.app.shared.data.model.GameType
import com.catsmoker.app.system.shell.ShellRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.EnumMap
import javax.inject.Inject

@HiltViewModel
class EditGameFilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellRunner: ShellRunner,
) : ViewModel(), Shizuku.OnRequestPermissionResultListener {

    sealed class EditEvent {
        data class Toast(val message: String, val isLong: Boolean = false) : EditEvent()
        object LaunchFilePicker : EditEvent()
        data class LaunchSafPicker(val message: String) : EditEvent()
        object LaunchFolderPicker : EditEvent()
        object LaunchAllFilesAccess : EditEvent()
        object ShowZArchiverDialog : EditEvent()
    }

    data class UiState(
        val isLoading: Boolean = false,
        val selectedGame: GameType = GameType.NONE,
        val selectedProfile: Int = 0,
        val selectedItemText: String = "",
        val showMethodChooser: Boolean = false,
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<EditEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<EditEvent> = _events.asSharedFlow()

    private val gameConfigs: MutableMap<GameType, GameConfig> = EnumMap(GameType::class.java)
    private val customUploadPrefs by lazy { context.getSharedPreferences("custom_upload_prefs", Context.MODE_PRIVATE) }

    private var selectedFileUri: Uri? = null
    private var selectedAssetPath: String? = null
    private var pendingAction: (() -> Unit)? = null
    private var customPackagePending: String? = null

    init {
        initializeGameConfigs()
        _uiState.update {
            it.copy(selectedItemText = context.getString(R.string.selected_item_placeholder))
        }
        Shizuku.addRequestPermissionResultListener(this)
    }

    override fun onCleared() {
        Shizuku.removeRequestPermissionResultListener(this)
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            checkAndStartShizukuAction()
        } else {
            showSnackbar("Shizuku permission denied.")
        }
    }

    private fun initializeGameConfigs() {
        val pubgGames = listOf(
            GameType.PUBG_GLOBAL to "com.tencent.ig",
            GameType.PUBG_KRJP to "com.pubg.krmobile",
            GameType.PUBG_VN to "com.vng.pubgmobile",
            GameType.BGMI to "com.pubg.imobile"
        )
        pubgGames.forEach { (type, pkg) ->
            gameConfigs[type] = buildPubgConfig(pkg)
        }
    }

    private fun buildPubgConfig(packageName: String): GameConfig {
        return GameConfig(
            packageName = packageName,
            saveDir = "/Android/data/$packageName/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/SaveGames/",
            saveFile = "Active.sav",
            maxFpsAssetPath = "PUBG/MaxFPS/Active.sav",
            ipadViewAssetPath = "PUBG/IpadVew/Active.sav"
        )
    }

    fun onGameSelected(game: GameType) = _uiState.update { it.copy(selectedGame = game) }
    fun onProfileSelected(profile: Int) = _uiState.update { it.copy(selectedProfile = profile) }

    fun onApplyProfile() {
        if (setSelectedAssetPathFromProfile()) {
            _uiState.update { it.copy(showMethodChooser = true) }
        }
    }

    fun dismissMethodChooser() = _uiState.update { it.copy(showMethodChooser = false) }

    fun onMethodSelected(which: Int) {
        _uiState.update { it.copy(showMethodChooser = false) }
        when (which) {
            0 -> checkAndStartShizukuAction()
            1 -> {
                val config = gameConfigs[_uiState.value.selectedGame]
                _events.tryEmit(EditEvent.LaunchSafPicker(config?.saveDir ?: ""))
            }
            2 -> {
                pendingAction = { handleZArchiverAction() }
                handleZArchiverAction()
            }
        }
    }

    fun onSelectFile() {
        _events.tryEmit(EditEvent.LaunchFilePicker)
    }

    fun onCustomFilePicked(uri: Uri) {
        setCustomSelection(uri)
    }

    fun onUploadFile() {
        uploadCustomContent(
            selectedFileUri,
            "Select a file first",
            context.getString(R.string.custom_upload_success_file),
            shAction = { uri, pkg -> uploadCustomFileWithShizuku(uri, pkg) },
            safAction = { uri, pkg -> uploadCustomFileWithSaf(uri, pkg) }
        )
    }

    fun onClearSelection() {
        setCustomSelection(null)
    }

    fun onSafPicked(treeUri: Uri) {
        performSafFileCopy(treeUri, _uiState.value.selectedGame)
    }

    private fun performSafFileCopy(treeUri: Uri, game: GameType) {
        val config = gameConfigs[game] ?: return
        val assetPath = requireSelectedAssetPath() ?: return
        launchIoWithLoading(successMessage = "Success via SAF!") {
            val pickedDir = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Cannot write")
            val existingFile = pickedDir.findFile(config.saveFile)
            val inputBytes = if (existingFile != null && existingFile.length() > 0) {
                context.contentResolver.openInputStream(existingFile.uri)?.use { it.readBytes() }
                    ?: context.assets.open(assetPath).use { it.readBytes() }
            } else {
                context.assets.open(assetPath).use { it.readBytes() }
            }
            val finalBytes = inputBytes 
            val file = existingFile ?: pickedDir.createFile(MIME_BINARY, config.saveFile) ?: throw IOException("Cannot create file")
            context.contentResolver.openOutputStream(file.uri, "w")?.use { it.write(finalBytes) }
            true
        }
    }

    fun onStoragePermissionResult() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
            pendingAction?.invoke()
            pendingAction = null
        }
    }

    fun onCustomFolderPicked(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            customPackagePending?.let { pkg ->
                customUploadPrefs.edit { putString(getCustomPrefsKey(pkg), uri.toString()) }
            }
            pendingAction?.invoke()
            pendingAction = null
        } catch (_: Exception) {}
    }

    fun onLaunchGame() {
        val config = gameConfigs[_uiState.value.selectedGame] ?: return
        val intent = context.packageManager.getLaunchIntentForPackage(config.packageName)
        if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            showSnackbar("Game not installed")
        }
    }

    private fun setSelectedAssetPathFromProfile(): Boolean {
        val config = gameConfigs[_uiState.value.selectedGame] ?: return false
        selectedAssetPath = if (_uiState.value.selectedProfile == 0) config.maxFpsAssetPath else config.ipadViewAssetPath
        if (selectedAssetPath == null) {
            showSnackbar(context.getString(R.string.apply_profile_first))
            return false
        }
        return true
    }

    private fun setCustomSelection(fileUri: Uri?) {
        selectedFileUri = fileUri
        _uiState.update {
            it.copy(
                selectedItemText = if (fileUri != null) {
                    context.getString(R.string.selected_item, getDisplayName(fileUri) ?: fileUri.toString())
                } else {
                    context.getString(R.string.selected_item_placeholder)
                }
            )
        }
    }

    private fun uploadCustomContent(
        uri: Uri?,
        msg: String,
        success: String,
        shAction: suspend (Uri, String) -> Boolean,
        safAction: (Uri, String) -> Boolean
    ) {
        val config = gameConfigs[_uiState.value.selectedGame] ?: return
        if (uri == null) {
            showSnackbar(context.getString(R.string.custom_upload_failed, msg))
            return
        }
        launchIoWithLoading(successMessage = success) {
            if (canUseShizukuForCustom()) shAction(uri, config.packageName)
            else safAction(uri, config.packageName)
        }
    }

    private fun canUseShizukuForCustom(): Boolean {
        if (!Shizuku.pingBinder()) return false
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(0)
            return false
        }
        return true
    }

    private suspend fun uploadCustomFileWithShizuku(fileUri: Uri, packageName: String): Boolean {
        val saveDir = "/storage/emulated/0/Android/data/$packageName/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/SaveGames"
        val targetPath = "$saveDir/Active.sav"
        val tempFile = File(context.externalCacheDir ?: context.cacheDir, "Custom_Active.sav")
        return try {
            context.contentResolver.openInputStream(fileUri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            } ?: return false
            shellRunner.execSafe("mkdir", "-p", saveDir)
            shellRunner.execSafe("cp", "-f", tempFile.absolutePath, targetPath)
            true
        } catch (_: Exception) {
            false
        } finally {
            tempFile.delete()
        }
    }

    private fun uploadCustomFileWithSaf(fileUri: Uri, packageName: String): Boolean {
        val treeUri = getCustomGameTreeUri(packageName)
            ?: run {
                requestCustomGameFolderAccess(packageName)
                return false
            }
        return try {
            val saveGamesDir = getOrCreateSaveGamesDir(treeUri) ?: return false
            saveGamesDir.findFile("Active.sav")?.delete()
            val target = saveGamesDir.createFile(MIME_BINARY, "Active.sav") ?: return false
            context.contentResolver.openInputStream(fileUri)?.use { input ->
                context.contentResolver.openOutputStream(target.uri, "w")?.use { output -> input.copyTo(output) }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun requestCustomGameFolderAccess(packageName: String) {
        pendingAction = null
        customPackagePending = packageName
        _events.tryEmit(EditEvent.LaunchFolderPicker)
        showSnackbar(context.getString(R.string.custom_upload_need_folder_access, packageName))
    }

    private fun getCustomGameTreeUri(packageName: String): Uri? =
        customUploadPrefs.getString(getCustomPrefsKey(packageName), null)?.toUri()

    private fun getCustomPrefsKey(packageName: String): String = "saf_tree_uri_$packageName"

    private fun getOrCreateSaveGamesDir(treeUri: Uri): DocumentFile? {
        val base = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        val pathSegments = listOf("files", "UE4Game", "ShadowTrackerExtra", "ShadowTrackerExtra", "Saved", "SaveGames")
        var current = base
        for (segment in pathSegments) {
            val next = current.findFile(segment) ?: current.createDirectory(segment)
            if (next == null) return null
            current = next
        }
        return current
    }

    private fun getDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1 && it.moveToFirst()) it.getString(index) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun checkAndStartShizukuAction() {
        val config = gameConfigs[_uiState.value.selectedGame] ?: return
        if (selectedAssetPath == null) {
            showSnackbar(context.getString(R.string.apply_profile_first))
            return
        }
        if (!Shizuku.pingBinder()) {
            showSnackbar("Shizuku is not running.")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(0)
        } else {
            performShizukuCopy(config)
        }
    }

    private fun performShizukuCopy(config: GameConfig) {
        val assetPath = requireSelectedAssetPath() ?: return
        launchIoWithLoading {
            var tempFile: File? = null
            try {
                tempFile = File(context.externalCacheDir ?: context.cacheDir, config.saveFile)
                tempFile.parentFile?.mkdirs()
                val destDir = Environment.getExternalStorageDirectory().path + config.saveDir
                val destPath = destDir + config.saveFile
                
                shellRunner.execSafe("cp", "-f", destPath, tempFile.absolutePath)
                
                val inputBytes: ByteArray = if (tempFile.exists() && tempFile.length() > 0) {
                    tempFile.readBytes()
                } else {
                    context.assets.open(assetPath).use { it.readBytes() }
                }
                tempFile.writeBytes(inputBytes)
                
                shellRunner.execSafe("mkdir", "-p", destDir)
                shellRunner.execSafe("cp", "-f", tempFile.absolutePath, destPath)
                
                withContext(Dispatchers.Main) {
                    showSnackbar("Success via Shizuku!")
                }
                true
            } catch (e: Exception) {
                throw e
            } finally {
                tempFile?.delete()
            }
        }
    }

    private fun handleZArchiverAction() {
        if (!checkStoragePermission()) return
        val config = gameConfigs[_uiState.value.selectedGame] ?: return
        val assetPath = requireSelectedAssetPath() ?: return
        launchIoWithLoading {
            pasteFileToDownloads(config, assetPath)
            withContext(Dispatchers.Main) { _events.tryEmit(EditEvent.ShowZArchiverDialog) }
            true
        }
    }

    @Throws(IOException::class)
    private fun pasteFileToDownloads(config: GameConfig, assetPath: String) {
        val inputBytes = context.assets.open(assetPath).use { it.readBytes() }
        val finalBytes = inputBytes 
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, config.saveFile)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
            ) ?: throw IOException("MediaStore failed")
            context.contentResolver.openOutputStream(uri)?.use { it.write(finalBytes) }
        } else {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), config.saveFile).writeBytes(finalBytes)
        }
    }

    fun launchZArchiver(): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(ZARCHIVER_PACKAGE)
        return if (intent != null) {
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } else {
            try {
                // Try market intent first
                val marketUri = "market://details?id=$ZARCHIVER_PACKAGE".toUri()
                val marketIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, marketUri).apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(marketIntent)
                true
            } catch (_: Exception) {
                try {
                    // Fallback to browser URL as requested
                    val webUri = "https://play.google.com/store/apps/details?id=$ZARCHIVER_PACKAGE&hl=en".toUri()
                    val webIntent = android.content.Intent(android.content.Intent.ACTION_VIEW, webUri).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(webIntent)
                    true
                } catch (_: Exception) {
                    false
                }
            }
        }
    }

    private fun checkStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                _events.tryEmit(EditEvent.LaunchAllFilesAccess)
                return false
            }
            return true
        }
        val granted = context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            _events.tryEmit(EditEvent.LaunchAllFilesAccess)
        }
        return granted
    }

    fun launchAllFilesAccess(): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return try {
                android.content.Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = "package:${context.packageName}".toUri()
                }
            } catch (_: Exception) {
                android.content.Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
            }
        }
        return null
    }

    fun showSnackbar(message: String) {
        _events.tryEmit(EditEvent.Toast(message, isLong = true))
    }

    private fun requireSelectedAssetPath(): String? {
        val path = selectedAssetPath
        if (path == null) showSnackbar(context.getString(R.string.apply_profile_first))
        return path
    }

    private fun launchIoWithLoading(successMessage: String? = null, task: suspend () -> Boolean) {
        _uiState.update { it.copy(isLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = try {
                task()
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showSnackbar("Failed: " + e.message) }
                false
            }
            withContext(Dispatchers.Main) {
                _uiState.update { it.copy(isLoading = false) }
                if (ok && successMessage != null) showSnackbar(successMessage)
            }
        }
    }

    private companion object {
        private const val MIME_BINARY = "application/octet-stream"
        private const val ZARCHIVER_PACKAGE = "ru.zdevs.zarchiver"
    }
}
