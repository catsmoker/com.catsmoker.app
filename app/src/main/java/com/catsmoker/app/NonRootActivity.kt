package com.catsmoker.app

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
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.catsmoker.app.databinding.ActivityNonRootBinding
import com.catsmoker.app.services.FileService
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnBinderDeadListener
import rikka.shizuku.Shizuku.OnBinderReceivedListener
import rikka.shizuku.Shizuku.OnRequestPermissionResultListener
import rikka.shizuku.Shizuku.UserServiceArgs
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume

class NonRootActivity : AppCompatActivity(), OnRequestPermissionResultListener {

    private lateinit var binding: ActivityNonRootBinding
    
    // Logic
    private var safLauncher: ActivityResultLauncher<Intent>? = null
    private var storagePermissionLauncher: ActivityResultLauncher<Intent>? = null
    private var pendingAction: Runnable? = null
    private var selectedAssetPath: String? = null
    private val gameConfigs: MutableMap<GameType, GameConfig> = HashMap()

    // Shizuku Listeners
    private val binderReceivedListener = OnBinderReceivedListener { 
        updateShizukuButtonState(Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) 
    }
    private val binderDeadListener = OnBinderDeadListener { 
        updateShizukuButtonState(false) 
    }

    enum class GameType(private val displayName: String) {
        NONE("Select a game"),
        PUBG_GLOBAL("PUBG Global");

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
        binding = ActivityNonRootBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        initializeGameConfigs()
        initializeUI()
        initializeLaunchers()
        setupListeners()

        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(this)
    }

    private fun setupToolbar() {
        supportActionBar?.apply {
            title = "Game File Manager"
            setDisplayHomeAsUpEnabled(true)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun initializeGameConfigs() {
        // NOTE: Ensure these asset paths actually exist in your src/main/assets folder!
        gameConfigs[GameType.PUBG_GLOBAL] = GameConfig(
            "com.tencent.ig",
            "/Android/data/com.tencent.ig/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/SaveGames/",
            "Active.sav",
            "PUBG Global/MaxFPS/Active.sav",
            "PUBG Global/IpadVew/Active.sav"
        )
    }

    private fun initializeUI() {
        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item, GameType.entries.toTypedArray()
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.gameSpinner.adapter = adapter
    }

    private fun initializeLaunchers() {
        safLauncher = registerForActivityResult(StartActivityForResult()) { result ->
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

        storagePermissionLauncher = registerForActivityResult(StartActivityForResult()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    showSnackbar("Storage permission granted")
                    pendingAction?.run()
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
        binding.btnApplyMaxFps.setOnClickListener {
            setSelectedAssetPath(true)
            showMethods()
        }
        binding.btnApplyIpadView.setOnClickListener {
            setSelectedAssetPath(false)
            showMethods()
        }
        binding.btnStartZArchiver.setOnClickListener {
            pendingAction = Runnable { handleZArchiverAction() }
            handleZArchiverAction()
        }
        binding.btnStartShizuku.setOnClickListener { checkAndStartShizukuAction() }
        binding.btnStartSaf.setOnClickListener {
            pendingAction = Runnable { launchSafPicker() }
            launchSafPicker()
        }
    }

    private fun updateButtonVisibility(game: GameType?) {
        val validGame = (game != GameType.NONE)
        val vis = if (validGame) View.VISIBLE else View.GONE
        
        with(binding) {
            btnLaunchGame.visibility = vis
            chooseOptionTitle.visibility = vis
            btnApplyMaxFps.visibility = vis
            btnApplyIpadView.visibility = vis
            
            chooseMethodTitle.visibility = View.GONE
            btnStartShizuku.visibility = View.GONE
            tonalButtonsLayout.visibility = View.GONE
        }
    }

    private fun showMethods() {
        with(binding) {
            chooseOptionTitle.visibility = View.GONE
            btnApplyMaxFps.visibility = View.GONE
            btnApplyIpadView.visibility = View.GONE
            
            chooseMethodTitle.visibility = View.VISIBLE
            btnStartShizuku.visibility = View.VISIBLE
            tonalButtonsLayout.visibility = View.VISIBLE
        }
    }

    private fun setSelectedAssetPath(isMaxFps: Boolean) {
        val config = selectedConfig
        if (config != null) {
            selectedAssetPath = if (isMaxFps) config.maxFpsAssetPath else config.ipadViewAssetPath
        }
    }

    private fun updateShizukuButtonState(granted: Boolean) {
        runOnUiThread {
            binding.btnStartShizuku.text = if (granted) "Apply via Shizuku" else "Grant Shizuku Permission"
        }
    }

    // --- Shizuku Logic ---
    private fun checkAndStartShizukuAction() {
        selectedConfig ?: return

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
            performShizukuCopy(selectedConfig!!)
        }
    }

    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            showSnackbar("Shizuku permission granted.")
            updateShizukuButtonState(true)
            checkAndStartShizukuAction()
        } else {
            showSnackbar("Shizuku permission denied.")
            updateShizukuButtonState(false)
        }
    }

    private fun performShizukuCopy(config: GameConfig) {
        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            var tempFile: File? = null
            try {
                // 1. Copy asset to app cache
                tempFile = File(cacheDir, config.saveFile)
                tempFile.parentFile?.mkdirs()

                assets.open(selectedAssetPath!!).use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                val sourcePath = tempFile.absolutePath
                val destDir = Environment.getExternalStorageDirectory().path + config.saveDir
                val destPath = destDir + config.saveFile

                // 2. Execute Shell Commands via Shizuku Service
                val cmdMkdir = arrayOf("sh", "-c", "mkdir -p \"$destDir\"")
                val exitMkdir = execShizukuCommand(cmdMkdir)
                if (exitMkdir != 0) throw IOException("Shizuku mkdir failed: $exitMkdir")

                val cmdCp = arrayOf("sh", "-c", "cp -f \"$sourcePath\" \"$destPath\"")
                val exitCp = execShizukuCommand(cmdCp)

                withContext(Dispatchers.Main) {
                    if (exitCp == 0) showSnackbar("Success! File replaced via Shizuku.")
                    else showSnackbar("Shizuku copy failed code: $exitCp")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Shizuku failed", e)
                withContext(Dispatchers.Main) { showSnackbar("Error: " + e.message) }
            } finally {
                tempFile?.delete()
                withContext(Dispatchers.Main) { setLoading(false) }
            }
        }
    }

    private suspend fun execShizukuCommand(command: Array<String>): Int = suspendCancellableCoroutine { cont ->
        val args = UserServiceArgs(
            ComponentName(packageName, FileService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("file_service")
            .debuggable(BuildConfig.DEBUG)
            .version(1)

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                try {
                    val fileService = IFileService.Stub.asInterface(service)
                    val result = fileService.executeCommand(command)
                    if (cont.isActive) cont.resume(result)
                } catch (e: Exception) {
                    Log.e(TAG, "Remote ex", e)
                    if (cont.isActive) cont.resume(-1)
                } finally {
                    try {
                        Shizuku.unbindUserService(args, this, true)
                    } catch (_: Exception) {}
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                // Should not happen for a simple command execution
            }
        }

        Shizuku.bindUserService(args, connection)
    }

    // --- ZArchiver Logic ---
    private fun handleZArchiverAction() {
        if (!checkStoragePermission()) return
        val config = selectedConfig ?: return

        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                pasteFileToDownloads(config)
                withContext(Dispatchers.Main) { showZArchiverSuccessDialog() }
            } catch (e: IOException) {
                withContext(Dispatchers.Main) { showSnackbar("Error: " + e.message) }
            } finally {
                withContext(Dispatchers.Main) { setLoading(false) }
            }
        }
    }

    @Throws(IOException::class)
    private fun pasteFileToDownloads(config: GameConfig) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, config.saveFile)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IOException("MediaStore failed")

            assets.open(selectedAssetPath!!).use { input ->
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
            assets.open(selectedAssetPath!!).use { input ->
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
                        startActivity(Intent(Intent.ACTION_VIEW, ("market://details?id=$ZARCHIVER_PACKAGE").toUri()))
                    } catch (_: Exception) {
                        showSnackbar("Play Store not found")
                    }
                }
            }.show()
    }

    // --- SAF Logic ---
    private fun launchSafPicker() {
        val config = selectedConfig ?: return
        showSnackbar("Select folder: " + config.saveDir)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        safLauncher?.launch(intent)
    }

    private fun performSafFileCopy(treeUri: Uri, game: GameType?) {
        if (game == GameType.NONE) return
        val config = gameConfigs[game] ?: return

        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pickedDir = DocumentFile.fromTreeUri(this@NonRootActivity, treeUri)
                if (pickedDir == null || !pickedDir.canWrite()) throw IOException("Cannot write")

                var targetFile = pickedDir.findFile(config.saveFile)
                if (targetFile == null) {
                    targetFile = pickedDir.createFile("application/octet-stream", config.saveFile)
                }

                targetFile?.let { file ->
                    assets.open(selectedAssetPath!!).use { input ->
                        contentResolver.openOutputStream(file.uri).use { output ->
                            if (output == null) throw IOException("SAF Output null")
                            input.copyTo(output)
                        }
                    }
                }
                withContext(Dispatchers.Main) { showSnackbar("Success via SAF!") }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { showSnackbar("SAF Error: " + e.message) }
            } finally {
                withContext(Dispatchers.Main) { setLoading(false) }
            }
        }
    }

    private val selectedConfig: GameConfig?
        get() {
            val selectedGame = binding.gameSpinner.selectedItem as? GameType
            if (selectedGame == GameType.NONE) {
                showSnackbar("Please select a game first.")
                return null
            }
            return gameConfigs[selectedGame]
        }

    private fun checkStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = "package:$packageName".toUri()
                    storagePermissionLauncher?.launch(intent)
                } catch (_: Exception) {
                    storagePermissionLauncher?.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
                return false
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                this, 
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    LEGACY_REQUEST_STORAGE_PERMISSION
                )
                return false
            }
        }
        return true
    }

    private fun launchGame() {
        val config = selectedConfig
        if (config != null) {
            val intent = packageManager.getLaunchIntentForPackage(config.packageName)
            if (intent != null) startActivity(intent)
            else showSnackbar("Game not installed")
        }
    }

    private fun setLoading(loading: Boolean) {
        with(binding) {
            progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            btnStartZArchiver.isEnabled = !loading
            btnStartShizuku.isEnabled = !loading
            btnStartSaf.isEnabled = !loading
            btnLaunchGame.isEnabled = !loading
            btnApplyMaxFps.isEnabled = !loading
            btnApplyIpadView.isEnabled = !loading
        }
    }

    private fun showSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(this)
    }

    companion object {
        private const val TAG = "NonRootActivity"
        private const val ZARCHIVER_PACKAGE = "ru.zdevs.zarchiver"
        private const val LEGACY_REQUEST_STORAGE_PERMISSION = 1001
    }
}