package com.catsmoker.app.ui.activities

import android.Manifest
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.catsmoker.app.BuildConfig
import com.catsmoker.app.IFileService
import com.catsmoker.app.R
import com.catsmoker.app.ui.screens.EditGameFilesScreen
import com.catsmoker.app.ui.theme.CatsmokerTheme
import com.catsmoker.app.util.ActiveSavModifier
import com.catsmoker.app.util.FileService
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.EnumMap
import kotlin.coroutines.resume

class EditGameFilesActivity : ComponentActivity(), Shizuku.OnRequestPermissionResultListener {

    private var isLoadingState by mutableStateOf(false)
    private var selectedGameByState by mutableStateOf(GameType.NONE)
    private var selectedProfileByState by mutableStateOf(0)
    private var selectedItemTextState by mutableStateOf("")

    private var selectedFileUri: Uri? = null
    private var selectedFolderUri: Uri? = null
    
    private var safLauncher: ActivityResultLauncher<Intent>? = null
    private var storagePermissionLauncher: ActivityResultLauncher<Intent>? = null
    private var pendingAction: Runnable? = null
    private var selectedAssetPath: String? = null
    private val gameConfigs: MutableMap<GameType, GameConfig> = EnumMap(GameType::class.java)
    private val customUploadPrefs by lazy { getSharedPreferences("custom_upload_prefs", MODE_PRIVATE) }

    private var customFilePickerLauncher: ActivityResultLauncher<Array<String>>? = null
    private var customFolderPickerLauncher: ActivityResultLauncher<Intent>? = null
    private var customGameFolderPickerLauncher: ActivityResultLauncher<Intent>? = null
    private var customPackagePending: String? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {}
    private val binderDeadListener = Shizuku.OnBinderDeadListener {}

    enum class GameType(val displayName: String) {
        NONE("Select a game"),
        PUBG_GLOBAL("PUBG Mobile (Global)"),
        PUBG_KRJP("PUBG Mobile Korea (KRJP)"),
        PUBG_VN("PUBG Mobile Vietnam"),
        BGMI("BGMI (India)")
    }

    data class GameConfig(
        val packageName: String,
        val saveDir: String,
        val saveFile: String,
        val maxFpsAssetPath: String?,
        val ipadViewAssetPath: String?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initializeGameConfigs()
        initializeLaunchers()
        
        selectedItemTextState = getString(R.string.selected_item_placeholder)

        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(this)

        setContent {
            CatsmokerTheme {
                EditGameFilesScreen(
                    isLoading = isLoadingState,
                    selectedGame = selectedGameByState,
                    selectedProfile = selectedProfileByState,
                    selectedItemText = selectedItemTextState,
                    onGameSelected = { selectedGameByState = it },
                    onProfileSelected = { selectedProfileByState = it },
                    onApplyProfile = { if (setSelectedAssetPathFromProfile()) showMethodChooserDialog() },
                    onSelectFile = { customFilePickerLauncher?.launch(arrayOf(MIME_BINARY, "*/*")) },
                    onSelectFolder = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        customFolderPickerLauncher?.launch(intent)
                    },
                    onUploadFile = { uploadCustomFile() },
                    onUploadFolder = { uploadCustomFolder() },
                    onClearSelection = { clearCustomSelection() },
                    onLaunchGame = { launchGame() },
                    onBack = { finish() }
                )
            }
        }
    }

    private fun initializeGameConfigs() {
        gameConfigs[GameType.PUBG_GLOBAL] = buildPubgConfig("com.tencent.ig")
        gameConfigs[GameType.PUBG_KRJP] = buildPubgConfig("com.pubg.krmobile")
        gameConfigs[GameType.PUBG_VN] = buildPubgConfig("com.vng.pubgmobile")
        gameConfigs[GameType.BGMI] = buildPubgConfig("com.pubg.imobile")
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

    private fun initializeLaunchers() {
        safLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val treeUri = result.data!!.data
                if (treeUri != null) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            treeUri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                        performSafFileCopy(treeUri, selectedGameByState)
                    } catch (e: SecurityException) {
                        showSnackbar("Failed to take permission: " + e.message)
                    }
                }
            }
        }

        storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                pendingAction?.run()
            }
        }

        customFilePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) setCustomSelection(fileUri = uri, folderUri = null)
        }

        customFolderPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data?.data != null) setCustomSelection(fileUri = null, folderUri = result.data!!.data!!)
        }

        customGameFolderPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data?.data != null) {
                val uri = result.data!!.data!!
                try {
                    contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    customPackagePending?.let { customUploadPrefs.edit { putString(getCustomPrefsKey(it), uri.toString()) } }
                    pendingAction?.run()
                    pendingAction = null
                } catch (_: Exception) {}
            }
        }
    }

    private fun showMethodChooserDialog() {
        val methods = arrayOf(
            getString(R.string.apply_with_shizuku_button),
            getString(R.string.apply_with_saf_button),
            getString(R.string.paste_to_downloads_button)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.select_apply_method)
            .setSingleChoiceItems(methods, -1) { picker, which ->
                when (which) {
                    0 -> checkAndStartShizukuAction()
                    1 -> { pendingAction = Runnable { launchSafPicker() }; launchSafPicker() }
                    2 -> { pendingAction = Runnable { handleZArchiverAction() }; handleZArchiverAction() }
                }
                picker.dismiss()
            }
            .setNegativeButton(R.string.cancel_button, null)
            .show()
    }

    private fun setSelectedAssetPathFromProfile(): Boolean {
        val config = gameConfigs[selectedGameByState] ?: return false
        selectedAssetPath = if (selectedProfileByState == 0) config.maxFpsAssetPath else config.ipadViewAssetPath
        if (selectedAssetPath == null) {
            showSnackbar(getString(R.string.apply_profile_first))
            return false
        }
        return true
    }

    private fun clearCustomSelection() {
        setCustomSelection(fileUri = null, folderUri = null)
    }

    private fun setCustomSelection(fileUri: Uri?, folderUri: Uri?) {
        selectedFileUri = fileUri
        selectedFolderUri = folderUri
        val selectedUri = fileUri ?: folderUri
        selectedItemTextState = if (selectedUri != null) {
            getString(R.string.selected_item, getDisplayName(selectedUri) ?: selectedUri.toString())
        } else {
            getString(R.string.selected_item_placeholder)
        }
    }

    private fun uploadCustomFile() {
        uploadCustomContent(selectedFileUri, "Select a file first", getString(R.string.custom_upload_success_file),
            { uri, pkg -> uploadCustomFileWithShizuku(uri, pkg) }, { uri, pkg -> uploadCustomFileWithSaf(uri, pkg) })
    }

    private fun uploadCustomFolder() {
        uploadCustomContent(selectedFolderUri, "Select a folder first", getString(R.string.custom_upload_success_folder),
            { uri, pkg -> uploadCustomFolderWithShizuku(uri, pkg) }, { uri, pkg -> uploadCustomFolderWithSaf(uri, pkg) })
    }

    private fun uploadCustomContent(uri: Uri?, msg: String, success: String, shAction: suspend (Uri, String) -> Boolean, safAction: (Uri, String) -> Boolean) {
        val config = gameConfigs[selectedGameByState] ?: return
        if (uri == null) { showSnackbar(getString(R.string.custom_upload_failed, msg)); return }
        launchIoWithLoading(successMessage = success) {
            if (canUseShizukuForCustom()) shAction(uri, config.packageName) else safAction(uri, config.packageName)
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
        val tempFile = File(externalCacheDir ?: cacheDir, "Custom_Active.sav")
        return try {
            contentResolver.openInputStream(fileUri)?.use { input -> FileOutputStream(tempFile).use { output -> input.copyTo(output) } } ?: return false
            execShizukuCommands(arrayOf("sh", "-c", "mkdir -p \"$saveDir\""), arrayOf("sh", "-c", "cp -f \"${tempFile.absolutePath}\" \"$targetPath\"")) == 0
        } catch (_: Exception) { false } finally { tempFile.delete() }
    }

    private suspend fun uploadCustomFolderWithShizuku(folderUri: Uri, packageName: String): Boolean {
        val targetDir = "/storage/emulated/0/Android/data/$packageName/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/SaveGames/file"
        val tempDir = File(externalCacheDir ?: cacheDir, "custom_folder")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()
        return try {
            val source = DocumentFile.fromTreeUri(this, folderUri) ?: return false
            copyDocumentTreeToLocal(source, tempDir)
            execShizukuCommands(arrayOf("sh", "-c", "rm -rf \"$targetDir\""), arrayOf("sh", "-c", "mkdir -p \"$targetDir\""), arrayOf("sh", "-c", "cp -r \"${tempDir.absolutePath}/.\" \"$targetDir/\"")) == 0
        } catch (_: Exception) { false } finally { tempDir.deleteRecursively() }
    }

    private fun uploadCustomFileWithSaf(fileUri: Uri, packageName: String): Boolean {
        val treeUri = getCustomGameTreeUri(packageName) ?: run { requestCustomGameFolderAccess(packageName) { uploadCustomFile() }; return false }
        return try {
            val saveGamesDir = getOrCreateSaveGamesDir(treeUri) ?: return false
            saveGamesDir.findFile("Active.sav")?.delete()
            val target = saveGamesDir.createFile(MIME_BINARY, "Active.sav") ?: return false
            contentResolver.openInputStream(fileUri)?.use { input -> contentResolver.openOutputStream(target.uri, "w")?.use { output -> input.copyTo(output) } }
            true
        } catch (_: Exception) { false }
    }

    private fun uploadCustomFolderWithSaf(folderUri: Uri, packageName: String): Boolean {
        val treeUri = getCustomGameTreeUri(packageName) ?: run { requestCustomGameFolderAccess(packageName) { uploadCustomFolder() }; return false }
        return try {
            val saveGamesDir = getOrCreateSaveGamesDir(treeUri) ?: return false
            saveGamesDir.findFile("file")?.let { deleteRecursively(it) }
            val newTarget = saveGamesDir.createDirectory("file") ?: return false
            val source = DocumentFile.fromTreeUri(this, folderUri) ?: return false
            copyDocumentTree(source, newTarget)
            true
        } catch (_: Exception) { false }
    }

    private fun requestCustomGameFolderAccess(packageName: String, onGranted: () -> Unit) {
        pendingAction = Runnable { onGranted() }
        customPackagePending = packageName
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        try { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, "content://com.android.externalstorage.documents/document/primary:Android/data/$packageName".toUri()) } catch (_: Exception) {}
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        customGameFolderPickerLauncher?.launch(intent)
        showSnackbar(getString(R.string.custom_upload_need_folder_access, packageName))
    }

    private fun getCustomGameTreeUri(packageName: String): Uri? = customUploadPrefs.getString(getCustomPrefsKey(packageName), null)?.toUri()
    private fun getCustomPrefsKey(packageName: String): String = "saf_tree_uri_$packageName"

    private fun getOrCreateSaveGamesDir(treeUri: Uri): DocumentFile? {
        val base = DocumentFile.fromTreeUri(this, treeUri) ?: return null
        val pathSegments = listOf("files", "UE4Game", "ShadowTrackerExtra", "ShadowTrackerExtra", "Saved", "SaveGames")
        var current = base
        for (segment in pathSegments) {
            val next = current.findFile(segment) ?: current.createDirectory(segment)
            if (next == null) return null
            current = next
        }
        return current
    }

    private fun copyDocumentTree(source: DocumentFile, targetDir: DocumentFile) {
        for (child in source.listFiles()) {
            if (child.isDirectory) {
                val newDir = targetDir.createDirectory(child.name ?: "folder") ?: continue
                copyDocumentTree(child, newDir)
            } else if (child.isFile) {
                val name = child.name ?: "file"
                targetDir.findFile(name)?.delete()
                val target = targetDir.createFile(child.type ?: MIME_BINARY, name) ?: continue
                contentResolver.openInputStream(child.uri)?.use { input -> contentResolver.openOutputStream(target.uri, "w")?.use { output -> input.copyTo(output) } }
            }
        }
    }

    private fun copyDocumentTreeToLocal(source: DocumentFile, targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()
        for (child in source.listFiles()) {
            if (child.isDirectory) copyDocumentTreeToLocal(child, File(targetDir, child.name ?: "folder"))
            else if (child.isFile) {
                val dest = File(targetDir, child.name ?: "file")
                contentResolver.openInputStream(child.uri)?.use { input -> FileOutputStream(dest).use { output -> input.copyTo(output) } }
            }
        }
    }

    private fun deleteRecursively(doc: DocumentFile) {
        if (doc.isDirectory) doc.listFiles().forEach { deleteRecursively(it) }
        doc.delete()
    }

    private fun getDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1 && it.moveToFirst()) it.getString(index) else null
            }
        } catch (_: Exception) { null }
    }

    private fun checkAndStartShizukuAction() {
        val config = gameConfigs[selectedGameByState] ?: return
        if (selectedAssetPath == null) { showSnackbar(getString(R.string.apply_profile_first)); return }
        if (!Shizuku.pingBinder()) { showSnackbar("Shizuku is not running."); return }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(0)
        else performShizukuCopy(config)
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        if (grantResult == PackageManager.PERMISSION_GRANTED) checkAndStartShizukuAction()
        else showSnackbar("Shizuku permission denied.")
    }

    private fun performShizukuCopy(config: GameConfig) {
        val assetPath = requireSelectedAssetPath() ?: return
        launchIoWithLoading {
            var tempFile: File? = null
            try {
                tempFile = File(externalCacheDir ?: cacheDir, config.saveFile)
                tempFile.parentFile?.mkdirs()
                val destDir = Environment.getExternalStorageDirectory().path + config.saveDir
                val destPath = destDir + config.saveFile
                val copyFromGameExit = execShizukuCommands(arrayOf("sh", "-c", "cp -f \"$destPath\" \"${tempFile.absolutePath}\""))
                val inputBytes: ByteArray = if (copyFromGameExit == 0 && tempFile.exists() && tempFile.length() > 0) tempFile.readBytes() else assets.open(assetPath).use { it.readBytes() }
                val finalBytes = if (selectedProfileByState == 0) ActiveSavModifier.modifyFps(inputBytes, 8) else inputBytes
                tempFile.writeBytes(finalBytes)
                val exitCp = execShizukuCommands(arrayOf("sh", "-c", "mkdir -p \"$destDir\""), arrayOf("sh", "-c", "cp -f \"${tempFile.absolutePath}\" \"$destPath\""))
                withContext(Dispatchers.Main) { if (exitCp == 0) showSnackbar("Success via Shizuku!") else showSnackbar("Shizuku failed code: $exitCp") }
                true
            } catch (e: Exception) { throw e } finally { tempFile?.delete() }
        }
    }

    private suspend fun execShizukuCommands(vararg commands: Array<String>): Int =
        withShizukuService { fileService ->
            for (command in commands) {
                val exitCode = fileService.executeCommand(command)
                if (exitCode != 0) return@withShizukuService exitCode
            }
            0
        }

    private suspend fun <T> withShizukuService(block: (IFileService) -> T): T =
        suspendCancellableCoroutine { cont ->
            val args = Shizuku.UserServiceArgs(ComponentName(packageName, FileService::class.java.name)).daemon(false).processNameSuffix("file_service").version(BuildConfig.VERSION_CODE)
            val connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    try {
                        val result = block(IFileService.Stub.asInterface(service))
                        if (cont.isActive) cont.resume(result)
                    } catch (e: Exception) { if (cont.isActive) cont.resumeWith(Result.failure(e)) } finally { Shizuku.unbindUserService(args, this, true) }
                }
                override fun onServiceDisconnected(name: ComponentName?) {}
            }
            Shizuku.bindUserService(args, connection)
        }

    private fun handleZArchiverAction() {
        if (!checkStoragePermission()) return
        val config = gameConfigs[selectedGameByState] ?: return
        val assetPath = requireSelectedAssetPath() ?: return
        launchIoWithLoading {
            pasteFileToDownloads(config, assetPath)
            withContext(Dispatchers.Main) { showZArchiverSuccessDialog() }
            true
        }
    }

    @Throws(IOException::class)
    private fun pasteFileToDownloads(config: GameConfig, assetPath: String) {
        val inputBytes = assets.open(assetPath).use { it.readBytes() }
        val finalBytes = if (selectedProfileByState == 0) ActiveSavModifier.modifyFps(inputBytes, 8) else inputBytes
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, config.saveFile); put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream"); put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS) }) ?: throw IOException("MediaStore failed")
            contentResolver.openOutputStream(uri)?.use { it.write(finalBytes) }
        } else {
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), config.saveFile).writeBytes(finalBytes)
        }
    }

    private fun showZArchiverSuccessDialog() {
        Snackbar.make(findViewById(android.R.id.content), "File saved to Downloads.", Snackbar.LENGTH_INDEFINITE).setAction("OPEN") {
            val intent = packageManager.getLaunchIntentForPackage(ZARCHIVER_PACKAGE)
            if (intent != null) startActivity(intent)
            else try { startActivity(Intent(Intent.ACTION_VIEW, ("market://details?id=$ZARCHIVER_PACKAGE").toUri())) } catch (_: Exception) {}
        }.show()
    }

    private fun launchSafPicker() {
        val config = gameConfigs[selectedGameByState] ?: return
        if (requireSelectedAssetPath() == null) return
        showSnackbar("Select folder: " + config.saveDir)
        safLauncher?.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) })
    }

    private fun performSafFileCopy(treeUri: Uri, game: GameType) {
        val config = gameConfigs[game] ?: return
        val assetPath = requireSelectedAssetPath() ?: return
        launchIoWithLoading(successMessage = "Success via SAF!") {
            val pickedDir = DocumentFile.fromTreeUri(this@EditGameFilesActivity, treeUri) ?: throw IOException("Cannot write")
            val existingFile = pickedDir.findFile(config.saveFile)
            val inputBytes = if (existingFile != null && existingFile.length() > 0) contentResolver.openInputStream(existingFile.uri)?.use { it.readBytes() } ?: assets.open(assetPath).use { it.readBytes() } else assets.open(assetPath).use { it.readBytes() }
            val finalBytes = if (selectedProfileByState == 0) ActiveSavModifier.modifyFps(inputBytes, 8) else inputBytes
            val file = existingFile ?: pickedDir.createFile(MIME_BINARY, config.saveFile) ?: throw IOException("Cannot create file")
            contentResolver.openOutputStream(file.uri, "w")?.use { it.write(finalBytes) }
            true
        }
    }

    private fun checkStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { if (!Environment.isExternalStorageManager()) { requestAllFilesAccess(); return false }; return true }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        if (!granted) ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), LEGACY_REQUEST_STORAGE_PERMISSION)
        return granted
    }

    private fun launchGame() {
        val config = gameConfigs[selectedGameByState] ?: return
        val intent = packageManager.getLaunchIntentForPackage(config.packageName)
        if (intent != null) startActivity(intent) else showSnackbar("Game not installed")
    }

    private fun showSnackbar(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }

    private fun requireSelectedAssetPath(): String? {
        val path = selectedAssetPath
        if (path == null) showSnackbar(getString(R.string.apply_profile_first))
        return path
    }

    private fun launchIoWithLoading(successMessage: String? = null, task: suspend () -> Boolean) {
        isLoadingState = true
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = try { task() } catch (e: Exception) { withContext(Dispatchers.Main) { showSnackbar("Failed: " + e.message) }; false }
            withContext(Dispatchers.Main) { isLoadingState = false; if (ok && successMessage != null) showSnackbar(successMessage) }
        }
    }

    private fun requestAllFilesAccess() {
        try { storagePermissionLauncher?.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply { data = "package:$packageName".toUri() }) } catch (_: Exception) { storagePermissionLauncher?.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(this)
    }

    companion object {
        private const val MIME_BINARY = "application/octet-stream"
        private const val ZARCHIVER_PACKAGE = "ru.zdevs.zarchiver"
        private const val LEGACY_REQUEST_STORAGE_PERMISSION = 1001
    }
}
