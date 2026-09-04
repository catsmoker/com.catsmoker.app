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
import com.catsmoker.app.shared.data.model.GameProfile
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
        data class LaunchSafPicker(val dir: String) : EditEvent()
        object LaunchFolderPicker : EditEvent()
        object LaunchAllFilesAccess : EditEvent()
        object ShowZArchiverDialog : EditEvent()
    }

    data class UiState(
        val isLoading: Boolean = false,
        val selectedGame: GameType = GameType.NONE,
        val selectedProfile: Int = 0,
        /** Labels of the selected game's profiles — per-game, since Genshin has one and PUBG two. */
        val profileLabels: List<String> = emptyList(),
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
        gameConfigs[GameType.GENSHIN_IMPACT] = buildGenshinConfig()
    }

    private fun buildPubgConfig(packageName: String): GameConfig {
        return GameConfig(
            packageName = packageName,
            saveDir = "/Android/data/$packageName/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/SaveGames/",
            saveFile = "Active.sav",
            profiles = listOf(
                GameProfile("Unlock 90 FPS", "PUBG/MaxFPS/Active.sav"),
                GameProfile("iPad View (Wide)", "PUBG/IpadVew/Active.sav")
            )
        )
    }

    /**
     * Genshin Impact, from the reference project `GenshinConfig-main`.
     *
     * The game reads `hardware_model_config.json` out of its own `files/` directory and looks the
     * entries up by the device's real model, so the tuned entry in the bundled template carries a
     * placeholder model that every delivery channel substitutes with `Build.MODEL` — the reference
     * README's manual instruction ("`Your Device Model` must match the model of the device you are
     * using"), automated. The asset is the reference's file byte-for-byte; it is deliberately not
     * valid JSON (unquoted `ASTC`, `01`, `00000001h`, trailing commas), and the game's lenient
     * parser accepts it exactly as shipped — so it is never parsed or re-serialised on the way
     * through, for the same reason the PUBG `Active.sav` blobs are shipped opaque.
     */
    private fun buildGenshinConfig(): GameConfig {
        return GameConfig(
            packageName = "com.miHoYo.GenshinImpact",
            saveDir = "/Android/data/com.miHoYo.GenshinImpact/files/",
            saveFile = "hardware_model_config.json",
            profiles = listOf(
                GameProfile("Vulkan + variable max FPS", "Genshin/hardware_model_config.json")
            ),
            requiresDeviceModel = true
        )
    }

    /** Switching games restarts the profile choice, so a PUBG index can never label a Genshin row. */
    fun onGameSelected(game: GameType) = _uiState.update {
        it.copy(
            selectedGame = game,
            selectedProfile = 0,
            profileLabels = gameConfigs[game]?.profiles?.map(GameProfile::label).orEmpty()
        )
    }
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

    /**
     * The bytes a delivery channel should push for [assetPath], after the game's own transform.
     *
     * Only the model-naming games have one: Genshin's template must carry the device's real
     * `Build.MODEL` or the game's lookup misses it and the pushed file changes nothing. Binary
     * blobs (PUBG) pass through untouched.
     */
    private fun assetBytes(config: GameConfig, assetPath: String): ByteArray {
        val raw = context.assets.open(assetPath).use { it.readBytes() }
        if (!config.requiresDeviceModel) return raw
        return GenshinConfigTemplate.withThisDevice(String(raw, Charsets.UTF_8))
            .toByteArray(Charsets.UTF_8)
    }

    private fun performSafFileCopy(treeUri: Uri, game: GameType) {
        val config = gameConfigs[game] ?: return
        val assetPath = requireSelectedAssetPath() ?: return
        launchIoWithLoading(successMessage = "Success via SAF!") {
            val pickedDir = DocumentFile.fromTreeUri(context, treeUri) ?: throw IOException("Cannot write")
            val existingFile = pickedDir.findFile(config.saveFile)
            // A model-named template is always the thing to push: the file already sitting there is
            // the game's stock config, and re-writing it would be a no-op dressed as success.
            // PUBG keeps its existing behaviour — the game's current save is preferred, the bundled
            // asset only when the slot is empty.
            val inputBytes = if (config.requiresDeviceModel || existingFile == null || existingFile.length() == 0L) {
                assetBytes(config, assetPath)
            } else {
                context.contentResolver.openInputStream(existingFile.uri)?.use { it.readBytes() }
                    ?: assetBytes(config, assetPath)
            }
            val file = existingFile ?: pickedDir.createFile(MIME_BINARY, config.saveFile) ?: throw IOException("Cannot create file")
            context.contentResolver.openOutputStream(file.uri, "w")?.use { it.write(inputBytes) }
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
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
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
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } else {
            showSnackbar("Game not installed")
        }
    }

    private fun setSelectedAssetPathFromProfile(): Boolean {
        val config = gameConfigs[_uiState.value.selectedGame] ?: return false
        val profiles = config.profiles
        // A profile index the game does not have (the leftover PUBG selection after switching to a
        // one-profile game) is clamped, not treated as missing — the user still gets a file to push.
        selectedAssetPath = profiles.getOrNull(
            _uiState.value.selectedProfile.coerceAtMost(profiles.lastIndex)
        )?.assetPath
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
        shAction: suspend (Uri, GameConfig) -> Boolean,
        safAction: (Uri, GameConfig) -> Boolean
    ) {
        val config = gameConfigs[_uiState.value.selectedGame] ?: return
        if (uri == null) {
            showSnackbar(context.getString(R.string.custom_upload_failed, msg))
            return
        }
        launchIoWithLoading(successMessage = success) {
            if (canUseShizukuForCustom()) shAction(uri, config)
            else safAction(uri, config)
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

    private suspend fun uploadCustomFileWithShizuku(fileUri: Uri, config: GameConfig): Boolean {
        // The game's own save directory and file, straight from the config — this path used to
        // hardcode PUBG's UE4Game tree, which sent a Genshin upload to a directory the game never
        // reads.
        val saveDir = "/storage/emulated/0" + config.saveDir.trimEnd('/')
        val targetPath = "$saveDir/${config.saveFile}"
        val tempFile = File(context.externalCacheDir ?: context.cacheDir, "Custom_${config.saveFile}")
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

    private fun uploadCustomFileWithSaf(fileUri: Uri, config: GameConfig): Boolean {
        val treeUri = getCustomGameTreeUri(config.packageName)
            ?: run {
                requestCustomGameFolderAccess(config.packageName)
                return false
            }
        return try {
            val saveGamesDir = getOrCreateTargetDir(treeUri, config) ?: return false
            saveGamesDir.findFile(config.saveFile)?.delete()
            val target = saveGamesDir.createFile(MIME_BINARY, config.saveFile) ?: return false
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

    private fun getOrCreateTargetDir(treeUri: Uri, config: GameConfig): DocumentFile? {
        val base = DocumentFile.fromTreeUri(context, treeUri) ?: return null
        // The tree the user picked is the game's Android/data root, and the config's saveDir says
        // where the game actually reads from — PUBG nests seven levels, Genshin sits directly in
        // files/. Derived rather than hardcoded so a game added without its own branch works.
        val pathSegments = config.saveDir.trim('/').split('/').filter { it.isNotBlank() }
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
            var pushFile: File? = null
            var existingCopy: File? = null
            try {
                val cacheDir = context.externalCacheDir ?: context.cacheDir
                val destDir = Environment.getExternalStorageDirectory().path + config.saveDir
                val destPath = destDir + config.saveFile

                // The game's current file, pulled in through the privileged shell for PUBG's
                // prefer-existing behaviour. It arrives owned by the privileged uid — the shell
                // (Shizuku, uid 2000) is the one that created it — so this app only ever *reads*
                // it, and never under a name this app later writes to. Rewriting it was the whole
                // bug: the push used to land on this same path, the app's own write was refused
                // with EACCES against the shell-owned file, and Genshin — whose config always
                // exists — failed on the very first apply. A failed read falls back to the
                // bundled asset rather than failing the push (a restrictive umask can make even
                // reading a shell-created file impossible).
                existingCopy = File.createTempFile("existing_", "_" + config.saveFile, cacheDir)
                existingCopy.delete() // cp will re-create it, as its own
                shellRunner.execSafe("cp", "-f", destPath, existingCopy.absolutePath)

                val inputBytes: ByteArray =
                    if (!config.requiresDeviceModel && existingCopy.exists() && existingCopy.length() > 0) {
                        runCatching { existingCopy.readBytes() }.getOrNull()
                            ?: assetBytes(config, assetPath)
                    } else {
                        // Same rule as the SAF channel: a model-named template is always the
                        // payload, because the file already on the device is the stock one the
                        // push exists to replace.
                        assetBytes(config, assetPath)
                    }

                // The push file is created and written by this app alone, under a fresh name on
                // every run, so no privileged-uid leftover can ever be sitting on it.
                pushFile = File.createTempFile("push_", "_" + config.saveFile, cacheDir)
                pushFile.writeBytes(inputBytes)

                shellRunner.execSafe("mkdir", "-p", destDir)
                shellRunner.execSafe("cp", "-f", pushFile.absolutePath, destPath)

                withContext(Dispatchers.Main) {
                    showSnackbar("Success via Shizuku!")
                }
                true
            } catch (e: Exception) {
                throw e
            } finally {
                pushFile?.delete()
                existingCopy?.delete()
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
        // Exported through the same transform as a direct push, so the manual file the user moves
        // with ZArchiver carries this device's model rather than the placeholder.
        val inputBytes = assetBytes(config, assetPath)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = context.contentResolver.insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                android.content.ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, config.saveFile)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
            ) ?: throw IOException("MediaStore failed")
            context.contentResolver.openOutputStream(uri)?.use { it.write(inputBytes) }
        } else {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), config.saveFile).writeBytes(inputBytes)
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
                val marketIntent = Intent(Intent.ACTION_VIEW, marketUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(marketIntent)
                true
            } catch (_: Exception) {
                try {
                    // Fallback to browser URL as requested
                    val webUri = "https://play.google.com/store/apps/details?id=$ZARCHIVER_PACKAGE&hl=en".toUri()
                    val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = "package:${context.packageName}".toUri()
                }
            } catch (_: Exception) {
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
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
