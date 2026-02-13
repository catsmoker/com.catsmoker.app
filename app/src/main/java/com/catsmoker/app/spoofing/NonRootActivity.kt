package com.catsmoker.app.spoofing

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
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.catsmoker.app.BuildConfig
import com.catsmoker.app.IFileService
import com.catsmoker.app.R
import com.catsmoker.app.safeDismiss
import com.catsmoker.app.safeShow
import com.catsmoker.app.databinding.ActivityNonRootScreenBinding
import com.catsmoker.app.main.setupScreenHeader
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

class NonRootActivity : AppCompatActivity(), Shizuku.OnRequestPermissionResultListener {

    private lateinit var binding: ActivityNonRootScreenBinding

    // Logic
    private var safLauncher: ActivityResultLauncher<Intent>? = null
    private var storagePermissionLauncher: ActivityResultLauncher<Intent>? = null
    private var pendingAction: Runnable? = null
    private var selectedAssetPath: String? = null
    private val gameConfigs: MutableMap<GameType, GameConfig> = EnumMap(GameType::class.java)
    private val customUploadPrefs by lazy { getSharedPreferences(CUSTOM_UPLOAD_PREFS, MODE_PRIVATE) }

    private var selectedFileUri: Uri? = null
    private var selectedFolderUri: Uri? = null
    private var customFilePickerLauncher: ActivityResultLauncher<Array<String>>? = null
    private var customFolderPickerLauncher: ActivityResultLauncher<Intent>? = null
    private var customGameFolderPickerLauncher: ActivityResultLauncher<Intent>? = null
    private var customPackagePending: String? = null

    // Shizuku Listeners
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {}
    private val binderDeadListener = Shizuku.OnBinderDeadListener {}

    enum class GameType(private val displayName: String) {
        NONE("Select a game"),
        PUBG_GLOBAL("PUBG Mobile (Global)"),
        PUBG_KRJP("PUBG Mobile Korea (KRJP)"),
        PUBG_VN("PUBG Mobile Vietnam"),
        BGMI("BGMI (India)");

        override fun toString(): String = displayName
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
        binding = ActivityNonRootScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupScreenHeader(R.string.non_root_file_manager_title, R.string.non_root_header_subtitle)
        binding.selectedItemText.text = getString(R.string.selected_item_placeholder)
        initializeGameConfigs()
        initializeUI()
        initializeLaunchers()
        setupListeners()

        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(this)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun initializeGameConfigs() {
        // All PUBG variants reuse the same module assets, but each has its own package path.
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

    private fun initializeUI() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item, GameType.entries.toTypedArray()
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.gameSpinner.adapter = adapter
        binding.profileToggleGroup.check(R.id.btn_apply_max_fps)
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
                        val selectedGame = binding.gameSpinner.selectedItem as? GameType
                        performSafFileCopy(treeUri, selectedGame)
                    } catch (e: SecurityException) {
                        showSnackbar("Failed to take permission: " + e.message)
                    }
                }
            }
        }

        storagePermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    showSnackbar("Storage permission granted")
                    pendingAction?.run()
                }
            }
        }

        customFilePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                setCustomSelection(fileUri = uri, folderUri = null)
            } else {
                showSnackbar(getString(R.string.custom_upload_failed, MSG_NO_FILE_SELECTED))
            }
        }

        customFolderPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data?.data != null) {
                val uri = result.data!!.data!!
                setCustomSelection(fileUri = null, folderUri = uri)
            } else {
                showSnackbar(getString(R.string.custom_upload_failed, MSG_NO_FOLDER_SELECTED))
            }
        }

        customGameFolderPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data?.data != null) {
                val uri = result.data!!.data!!
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    val pkg = customPackagePending
                    if (!pkg.isNullOrBlank()) {
                        customUploadPrefs.edit { putString(getCustomPrefsKey(pkg), uri.toString()) }
                    }
                    pendingAction?.run()
                    pendingAction = null
                } catch (e: SecurityException) {
                    showSnackbar(getString(R.string.custom_upload_failed, e.message ?: "Permission error"))
                }
            }
        }
    }

    private fun setupListeners() {
        binding.gameSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val selectedGame = parent.getItemAtPosition(position) as? GameType
                updateButtonVisibility(selectedGame)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                updateButtonVisibility(GameType.NONE)
            }
        }

        binding.btnLaunchGame.setOnClickListener { launchGame() }
        binding.btnApplyProfile.setOnClickListener {
            if (setSelectedAssetPathFromProfile()) {
                showMethodChooserDialog()
            }
        }

        binding.btnSelectFile.setOnClickListener {
            customFilePickerLauncher?.launch(arrayOf(MIME_BINARY, "*/*"))
        }

        binding.btnSelectFolder.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            customFolderPickerLauncher?.launch(intent)
        }

        binding.btnUploadFile.setOnClickListener { uploadCustomFile() }
        binding.btnUploadFolder.setOnClickListener { uploadCustomFolder() }
        binding.btnClearSelection.setOnClickListener { clearCustomSelection() }
    }

    private fun updateButtonVisibility(game: GameType?) {
        val validGame = (game != GameType.NONE)
        val vis = if (validGame) View.VISIBLE else View.GONE

        with(binding) {
            btnLaunchGame.visibility = vis
            chooseOptionTitle.visibility = vis
            profileToggleGroup.visibility = vis
            btnApplyProfile.visibility = vis
            listOf(
                btnSelectFile,
                btnSelectFolder,
                btnUploadFile,
                btnUploadFolder,
                btnClearSelection
            ).forEach { it.isEnabled = validGame }
        }
    }

    private fun showMethodChooserDialog() {
        val methods = arrayOf(
            getString(R.string.apply_with_shizuku_button),
            getString(R.string.apply_with_saf_button),
            getString(R.string.paste_to_downloads_button)
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.select_apply_method)
            .setSingleChoiceItems(methods, -1) { picker, which ->
                when (which) {
                    0 -> checkAndStartShizukuAction()
                    1 -> {
                        pendingAction = Runnable { launchSafPicker() }
                        launchSafPicker()
                    }
                    2 -> {
                        pendingAction = Runnable { handleZArchiverAction() }
                        handleZArchiverAction()
                    }
                }
                (picker as? AlertDialog)?.safeDismiss(TAG)
            }
            .setNegativeButton(R.string.cancel_button, null)
            .create()

        dialog.safeShow(TAG)
    }

    private fun setSelectedAssetPathFromProfile(): Boolean {
        val config = selectedConfig ?: return false
        selectedAssetPath = when (binding.profileToggleGroup.checkedButtonId) {
            R.id.btn_apply_max_fps -> config.maxFpsAssetPath
            R.id.btn_apply_ipad_view -> config.ipadViewAssetPath
            else -> null
        }

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
        binding.selectedItemText.text = if (selectedUri != null) {
            getString(
                R.string.selected_item,
                getDisplayName(selectedUri) ?: selectedUri.toString()
            )
        } else {
            getString(R.string.selected_item_placeholder)
        }
    }

    private fun uploadCustomFile() {
        uploadCustomContent(
            selectedUri = selectedFileUri,
            missingSourceMessage = "Select a file first",
            successMessage = getString(R.string.custom_upload_success_file),
            shizukuAction = { uri, pkg -> uploadCustomFileWithShizuku(uri, pkg) },
            safAction = { uri, pkg -> uploadCustomFileWithSaf(uri, pkg) }
        )
    }

    private fun uploadCustomFolder() {
        uploadCustomContent(
            selectedUri = selectedFolderUri,
            missingSourceMessage = "Select a folder first",
            successMessage = getString(R.string.custom_upload_success_folder),
            shizukuAction = { uri, pkg -> uploadCustomFolderWithShizuku(uri, pkg) },
            safAction = { uri, pkg -> uploadCustomFolderWithSaf(uri, pkg) }
        )
    }

    private fun uploadCustomContent(
        selectedUri: Uri?,
        missingSourceMessage: String,
        successMessage: String,
        shizukuAction: suspend (Uri, String) -> Boolean,
        safAction: (Uri, String) -> Boolean
    ) {
        val config = selectedConfig ?: return
        val uri = selectedUri
        if (uri == null) {
            showSnackbar(getString(R.string.custom_upload_failed, missingSourceMessage))
            return
        }

        launchIoWithLoading(successMessage = successMessage) {
            if (canUseShizukuForCustom()) {
                shizukuAction(uri, config.packageName)
            } else {
                safAction(uri, config.packageName)
            }
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
        val tempFile = File(cacheDir, "Custom_Active.sav")

        return try {
            contentResolver.openInputStream(fileUri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            } ?: return false

            execShizukuCommands(
                arrayOf("sh", "-c", "mkdir -p \"$saveDir\""),
                arrayOf("sh", "-c", "cp -f \"${tempFile.absolutePath}\" \"$targetPath\"")
            ) == 0
        } catch (e: Exception) {
            Log.e(TAG, "Custom upload via Shizuku failed", e)
            false
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun uploadCustomFolderWithShizuku(folderUri: Uri, packageName: String): Boolean {
        val targetDir =
            "/storage/emulated/0/Android/data/$packageName/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/SaveGames/file"
        val tempDir = File(cacheDir, "custom_folder")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()

        return try {
            val source = DocumentFile.fromTreeUri(this, folderUri) ?: return false
            copyDocumentTreeToLocal(source, tempDir)

            execShizukuCommands(
                arrayOf("sh", "-c", "rm -rf \"$targetDir\""),
                arrayOf("sh", "-c", "mkdir -p \"$targetDir\""),
                arrayOf("sh", "-c", "cp -r \"${tempDir.absolutePath}/.\" \"$targetDir/\"")
            ) == 0
        } catch (e: Exception) {
            Log.e(TAG, "Custom folder upload via Shizuku failed", e)
            false
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun uploadCustomFileWithSaf(fileUri: Uri, packageName: String): Boolean {
        val treeUri = getCustomGameTreeUri(packageName) ?: run {
            requestCustomGameFolderAccess(packageName) { uploadCustomFile() }
            return false
        }

        return try {
            val saveGamesDir = getOrCreateSaveGamesDir(treeUri) ?: return false
            val existing = saveGamesDir.findFile("Active.sav")
            existing?.delete()

            val target = saveGamesDir.createFile(MIME_BINARY, "Active.sav") ?: return false
            contentResolver.openInputStream(fileUri)?.use { input ->
                contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Custom upload SAF failed", e)
            false
        }
    }

    private fun uploadCustomFolderWithSaf(folderUri: Uri, packageName: String): Boolean {
        val treeUri = getCustomGameTreeUri(packageName) ?: run {
            requestCustomGameFolderAccess(packageName) { uploadCustomFolder() }
            return false
        }

        return try {
            val saveGamesDir = getOrCreateSaveGamesDir(treeUri) ?: return false
            val targetFolder = saveGamesDir.findFile("file")
            if (targetFolder != null) deleteRecursively(targetFolder)

            val newTarget = saveGamesDir.createDirectory("file") ?: return false
            val source = DocumentFile.fromTreeUri(this, folderUri) ?: return false
            copyDocumentTree(source, newTarget)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Custom folder SAF failed", e)
            false
        }
    }

    private fun requestCustomGameFolderAccess(packageName: String, onGranted: () -> Unit) {
        pendingAction = Runnable { onGranted() }
        customPackagePending = packageName
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        try {
            val initialUri =
                "content://com.android.externalstorage.documents/document/primary:Android/data/$packageName".toUri()
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        } catch (_: Exception) {}
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        customGameFolderPickerLauncher?.launch(intent)
        showSnackbar(getString(R.string.custom_upload_need_folder_access, packageName))
    }

    private fun getCustomGameTreeUri(packageName: String): Uri? {
        val saved = customUploadPrefs.getString(getCustomPrefsKey(packageName), null) ?: return null
        return saved.toUri()
    }

    private fun getCustomPrefsKey(packageName: String): String = "saf_tree_uri_$packageName"

    private fun getOrCreateSaveGamesDir(treeUri: Uri): DocumentFile? {
        val base = DocumentFile.fromTreeUri(this, treeUri) ?: return null
        val pathSegments = listOf(
            "files",
            "UE4Game",
            "ShadowTrackerExtra",
            "ShadowTrackerExtra",
            "Saved",
            "SaveGames"
        )
        var current = base
        for (segment in pathSegments) {
            val next = current.findFile(segment) ?: current.createDirectory(segment)
            if (next == null) return null
            current = next
        }
        return current
    }

    private fun copyDocumentTree(source: DocumentFile, targetDir: DocumentFile) {
        val children = source.listFiles()
        for (child in children) {
            if (child.isDirectory) {
                val newDir = targetDir.createDirectory(child.name ?: "folder") ?: continue
                copyDocumentTree(child, newDir)
            } else if (child.isFile) {
                val name = child.name ?: "file"
                val existing = targetDir.findFile(name)
                existing?.delete()
                val target = targetDir.createFile(child.type ?: MIME_BINARY, name) ?: continue
                contentResolver.openInputStream(child.uri)?.use { input ->
                    contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun copyDocumentTreeToLocal(source: DocumentFile, targetDir: File) {
        if (!targetDir.exists()) targetDir.mkdirs()
        for (child in source.listFiles()) {
            if (child.isDirectory) {
                val dest = File(targetDir, child.name ?: "folder")
                copyDocumentTreeToLocal(child, dest)
            } else if (child.isFile) {
                val dest = File(targetDir, child.name ?: "file")
                contentResolver.openInputStream(child.uri)?.use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
            }
        }
    }

    private fun deleteRecursively(doc: DocumentFile) {
        if (doc.isDirectory) {
            doc.listFiles().forEach { deleteRecursively(it) }
        }
        doc.delete()
    }

    private fun getDisplayName(uri: Uri): String? {
        return try {
            val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            cursor?.use {
                val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index != -1 && it.moveToFirst()) it.getString(index) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    // --- Shizuku Logic ---
    private fun checkAndStartShizukuAction() {
        val config = selectedConfig ?: return
        if (selectedAssetPath == null) {
            showSnackbar(getString(R.string.apply_profile_first))
            return
        }

        if (!Shizuku.pingBinder()) {
            showSnackbar("Shizuku is not running. Please start the Shizuku app.")
            return
        }

        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                showSnackbar("Shizuku permission required.")
            }
            Shizuku.requestPermission(0)
        } else {
            performShizukuCopy(config)
        }
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            showSnackbar("Shizuku permission granted.")
            checkAndStartShizukuAction()
        } else {
            showSnackbar("Shizuku permission denied.")
        }
    }

    private fun performShizukuCopy(config: GameConfig) {
        val assetPath = requireSelectedAssetPath() ?: return
        launchIoWithLoading(
            onError = { e -> showSnackbar("Error: " + e.message) }
        ) {
            var tempFile: File? = null
            try {
                // 1. Copy asset to app cache
                tempFile = File(cacheDir, config.saveFile)
                tempFile.parentFile?.mkdirs()

                assets.open(assetPath).use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val sourcePath = tempFile.absolutePath
                val destDir = Environment.getExternalStorageDirectory().path + config.saveDir
                val destPath = destDir + config.saveFile

                // 2. Execute shell commands via one Shizuku service bind
                val exitCp = execShizukuCommands(
                    arrayOf("sh", "-c", "mkdir -p \"$destDir\""),
                    arrayOf("sh", "-c", "cp -f \"$sourcePath\" \"$destPath\"")
                )

                withContext(Dispatchers.Main) {
                    if (exitCp == 0) showSnackbar("Success! File replaced via Shizuku.")
                    else showSnackbar("Shizuku copy failed code: $exitCp")
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Shizuku failed", e)
                throw e
            } finally {
                tempFile?.delete()
            }
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
            val args = Shizuku.UserServiceArgs(
                ComponentName(packageName, FileService::class.java.name)
            )
                .daemon(false)
                .processNameSuffix("file_service")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE)

            var bound = false
            lateinit var connection: ServiceConnection
            fun unbindSafely() {
                if (!bound) return
                try {
                    Shizuku.unbindUserService(args, connection, true)
                } catch (_: Exception) {
                } finally {
                    bound = false
                }
            }

            connection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    try {
                        val fileService = IFileService.Stub.asInterface(service)
                        val result = block(fileService)
                        if (cont.isActive) cont.resume(result)
                    } catch (e: Exception) {
                        Log.e(TAG, "Remote ex", e)
                        if (cont.isActive) cont.resumeWith(Result.failure(e))
                    } finally {
                        unbindSafely()
                    }
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    // Should not happen for a simple command execution
                }
            }

            cont.invokeOnCancellation { unbindSafely() }
            bound = true
            Shizuku.bindUserService(args, connection)
        }

    // --- ZArchiver Logic ---
    private fun handleZArchiverAction() {
        if (!checkStoragePermission()) return
        val config = selectedConfig ?: return
        val assetPath = requireSelectedAssetPath() ?: return

        launchIoWithLoading(
            onError = { e -> showSnackbar("Error: " + e.message) }
        ) {
            pasteFileToDownloads(config, assetPath)
            withContext(Dispatchers.Main) { showZArchiverSuccessDialog() }
            true
        }
    }

    @Throws(IOException::class)
    private fun pasteFileToDownloads(config: GameConfig, assetPath: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, config.saveFile)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException("MediaStore failed")

            assets.open(assetPath).use { input ->
                contentResolver.openOutputStream(uri).use { output ->
                    if (output == null) throw IOException("Output null")
                    input.copyTo(output)
                }
            }
        } else {
            val dest = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                config.saveFile
            )
            assets.open(assetPath).use { input ->
                FileOutputStream(dest).use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    private fun showZArchiverSuccessDialog() {
        Snackbar.make(binding.root, "File saved to Downloads. Open ZArchiver?", Snackbar.LENGTH_INDEFINITE)
            .setAction("OPEN") {
                val intent = packageManager.getLaunchIntentForPackage(ZARCHIVER_PACKAGE)
                if (intent != null) startActivity(intent)
                else {
                    try {
                        startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                ("market://details?id=$ZARCHIVER_PACKAGE").toUri()
                            )
                        )
                    } catch (_: Exception) {
                        showSnackbar("Play Store not found")
                    }
                }
            }.show()
    }

    // --- SAF Logic ---
    private fun launchSafPicker() {
        val config = selectedConfig ?: return
        if (requireSelectedAssetPath() == null) return
        showSnackbar("Select folder: " + config.saveDir)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        safLauncher?.launch(intent)
    }

    private fun performSafFileCopy(treeUri: Uri, game: GameType?) {
        if (game == GameType.NONE) return
        val config = gameConfigs[game] ?: return
        val assetPath = requireSelectedAssetPath() ?: return

        launchIoWithLoading(
            onError = { e -> showSnackbar("SAF Error: " + e.message) }
        ) {
            val pickedDir = DocumentFile.fromTreeUri(this@NonRootActivity, treeUri)
            if (pickedDir == null || !pickedDir.canWrite()) throw IOException("Cannot write")

            var targetFile = pickedDir.findFile(config.saveFile)
            if (targetFile == null) {
                targetFile = pickedDir.createFile(MIME_BINARY, config.saveFile)
            }
            val file = targetFile ?: throw IOException("Cannot create target file")

            assets.open(assetPath).use { input ->
                contentResolver.openOutputStream(file.uri).use { output ->
                    if (output == null) throw IOException("SAF Output null")
                    input.copyTo(output)
                }
            }
            withContext(Dispatchers.Main) { showSnackbar("Success via SAF!") }
            true
        }
    }

    private val selectedConfig: GameConfig?
        get() {
            val selectedGame = binding.gameSpinner.selectedItem as? GameType
            if (selectedGame == null || selectedGame == GameType.NONE) {
                showSnackbar("Please select a game first.")
                return null
            }
            return gameConfigs[selectedGame]
        }

    private fun checkStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                requestAllFilesAccess()
                return false
            }
            return true
        }

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return true
        ActivityCompat.requestPermissions(
            this,
            arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
            LEGACY_REQUEST_STORAGE_PERMISSION
        )
        return false
    }

    private fun launchGame() {
        val config = selectedConfig ?: return
        val intent = packageManager.getLaunchIntentForPackage(config.packageName)
        if (intent != null) startActivity(intent) else showSnackbar("Game not installed")
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        listOf(
            binding.btnLaunchGame,
            binding.btnApplyMaxFps,
            binding.btnApplyIpadView,
            binding.btnApplyProfile,
            binding.btnSelectFile,
            binding.btnSelectFolder,
            binding.btnUploadFile,
            binding.btnUploadFolder,
            binding.btnClearSelection
        ).forEach { it.isEnabled = !loading }
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    private fun requireSelectedAssetPath(): String? {
        val assetPath = selectedAssetPath
        if (assetPath == null) {
            showSnackbar(getString(R.string.apply_profile_first))
        }
        return assetPath
    }

    private fun launchIoWithLoading(
        successMessage: String? = null,
        onError: (Exception) -> Unit = { e -> showSnackbar(getString(R.string.custom_upload_failed, e.message ?: "Unknown error")) },
        task: suspend () -> Boolean
    ) {
        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val ok = try {
                task()
            } catch (e: Exception) {
                Log.e(TAG, "Background task failed", e)
                withContext(Dispatchers.Main) { onError(e) }
                false
            }
            withContext(Dispatchers.Main) {
                setLoading(false)
                if (ok && successMessage != null) {
                    showSnackbar(successMessage)
                }
            }
        }
    }

    private fun requestAllFilesAccess() {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply {
                data = "package:$packageName".toUri()
            }
            storagePermissionLauncher?.launch(intent)
        } catch (_: Exception) {
            storagePermissionLauncher?.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(this)
    }

    companion object {
        private const val TAG = "NonRootActivity"
        private const val CUSTOM_UPLOAD_PREFS = "custom_upload_prefs"
        private const val MIME_BINARY = "application/octet-stream"
        private const val MSG_NO_FILE_SELECTED = "No file selected"
        private const val MSG_NO_FOLDER_SELECTED = "No folder selected"
        private const val ZARCHIVER_PACKAGE = "ru.zdevs.zarchiver"
        private const val LEGACY_REQUEST_STORAGE_PERMISSION = 1001
    }
}
