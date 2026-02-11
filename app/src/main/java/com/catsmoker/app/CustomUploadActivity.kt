package com.catsmoker.app

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.catsmoker.app.databinding.ActivityCustomUploadBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.UserServiceArgs
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import androidx.core.content.edit
import androidx.core.net.toUri

class CustomUploadActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCustomUploadBinding
    private var selectedFileUri: Uri? = null
    private var selectedFolderUri: Uri? = null
    private var pendingAction: (() -> Unit)? = null

    private var gamePackage: String? = null
    private var gameName: String? = null

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private lateinit var filePickerLauncher: ActivityResultLauncher<Array<String>>
    private lateinit var folderPickerLauncher: ActivityResultLauncher<Intent>
    private lateinit var gameFolderPickerLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCustomUploadBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupScreenHeader(R.string.custom_upload_title, R.string.custom_upload_subtitle)

        gamePackage = intent.getStringExtra(EXTRA_GAME_PACKAGE)
        gameName = intent.getStringExtra(EXTRA_GAME_NAME)

        if (gamePackage.isNullOrBlank()) {
            Toast.makeText(this, getString(R.string.custom_upload_missing_game), Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.gameNameText.text = gameName ?: gamePackage
        binding.selectedItemText.text = getString(R.string.selected_item_placeholder)

        initLaunchers()
        setupListeners()
    }

    private fun initLaunchers() {
        filePickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                selectedFileUri = uri
                selectedFolderUri = null
                binding.selectedItemText.text = getString(R.string.selected_item, getDisplayName(uri) ?: uri.toString())
            } else {
                showToast(getString(R.string.custom_upload_failed, "No file selected"))
            }
        }

        folderPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data?.data != null) {
                val uri = result.data!!.data!!
                selectedFolderUri = uri
                selectedFileUri = null
                binding.selectedItemText.text = getString(R.string.selected_item, getDisplayName(uri) ?: uri.toString())
            } else {
                showToast(getString(R.string.custom_upload_failed, "No folder selected"))
            }
        }

        gameFolderPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data?.data != null) {
                val uri = result.data!!.data!!
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    prefs.edit { putString(getPrefsKey(), uri.toString()) }
                    pendingAction?.invoke()
                    pendingAction = null
                } catch (e: SecurityException) {
                    showToast(getString(R.string.custom_upload_failed, e.message ?: "Permission error"))
                }
            }
        }
    }

    private fun setupListeners() {
        binding.btnSelectFile.setOnClickListener {
            filePickerLauncher.launch(arrayOf("application/octet-stream", "*/*"))
        }

        binding.btnSelectFolder.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            folderPickerLauncher.launch(intent)
        }

        binding.btnUploadFile.setOnClickListener { uploadFile() }
        binding.btnUploadFolder.setOnClickListener { uploadFolder() }
        binding.btnClearSelection.setOnClickListener { clearSelection() }
    }

    private fun clearSelection() {
        selectedFileUri = null
        selectedFolderUri = null
        binding.selectedItemText.text = getString(R.string.selected_item_placeholder)
    }

    private fun uploadFile() {
        val uri = selectedFileUri ?: run {
            showToast(getString(R.string.custom_upload_failed, "Select a file first"))
            return
        }
        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val result = if (canUseShizuku()) {
                uploadFileWithShizuku(uri)
            } else {
                uploadFileWithSaf(uri)
            }
            withContext(Dispatchers.Main) {
                setLoading(false)
                if (result) showToast(getString(R.string.custom_upload_success_file))
            }
        }
    }

    private fun uploadFolder() {
        val uri = selectedFolderUri ?: run {
            showToast(getString(R.string.custom_upload_failed, "Select a folder first"))
            return
        }
        setLoading(true)
        lifecycleScope.launch(Dispatchers.IO) {
            val result = if (canUseShizuku()) {
                uploadFolderWithShizuku(uri)
            } else {
                uploadFolderWithSaf(uri)
            }
            withContext(Dispatchers.Main) {
                setLoading(false)
                if (result) showToast(getString(R.string.custom_upload_success_folder))
            }
        }
    }

    private fun canUseShizuku(): Boolean {
        if (!Shizuku.pingBinder()) return false
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(0)
            return false
        }
        return true
    }

    private suspend fun uploadFileWithShizuku(fileUri: Uri): Boolean {
        val pkg = gamePackage ?: return false
        val saveDir = "/storage/emulated/0/Android/data/$pkg/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/SaveGames"
        val targetPath = "$saveDir/Active.sav"
        val tempFile = File(cacheDir, "Custom_Active.sav")

        return try {
            contentResolver.openInputStream(fileUri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            } ?: return false

            val mkdir = execShizukuCommand(arrayOf("sh", "-c", "mkdir -p \"$saveDir\""))
            if (mkdir != 0) return false

            val copy = execShizukuCommand(arrayOf("sh", "-c", "cp -f \"${tempFile.absolutePath}\" \"$targetPath\""))
            copy == 0
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku file upload failed", e)
            false
        } finally {
            tempFile.delete()
        }
    }

    private suspend fun uploadFolderWithShizuku(folderUri: Uri): Boolean {
        val pkg = gamePackage ?: return false
        val targetDir = "/storage/emulated/0/Android/data/$pkg/files/UE4Game/ShadowTrackerExtra/ShadowTrackerExtra/Saved/SaveGames/file"
        val tempDir = File(cacheDir, "custom_folder")
        if (tempDir.exists()) tempDir.deleteRecursively()
        tempDir.mkdirs()

        return try {
            val source = DocumentFile.fromTreeUri(this, folderUri) ?: return false
            copyDocumentTreeToLocal(source, tempDir)

            val rm = execShizukuCommand(arrayOf("sh", "-c", "rm -rf \"$targetDir\""))
            if (rm != 0) return false

            val mkdir = execShizukuCommand(arrayOf("sh", "-c", "mkdir -p \"$targetDir\""))
            if (mkdir != 0) return false

            val copy = execShizukuCommand(arrayOf("sh", "-c", "cp -r \"${tempDir.absolutePath}/.\" \"$targetDir/\""))
            copy == 0
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku folder upload failed", e)
            false
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun uploadFileWithSaf(fileUri: Uri): Boolean {
        val treeUri = getGameTreeUri() ?: run {
            requestGameFolderAccess { uploadFile() }
            return false
        }

        return try {
            val saveGamesDir = getOrCreateSaveGamesDir(treeUri) ?: return false
            val existing = saveGamesDir.findFile("Active.sav")
            existing?.delete()

            val target = saveGamesDir.createFile("application/octet-stream", "Active.sav") ?: return false
            contentResolver.openInputStream(fileUri)?.use { input ->
                contentResolver.openOutputStream(target.uri, "w")?.use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "SAF file upload failed", e)
            false
        }
    }

    private fun uploadFolderWithSaf(folderUri: Uri): Boolean {
        val treeUri = getGameTreeUri() ?: run {
            requestGameFolderAccess { uploadFolder() }
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
            Log.e(TAG, "SAF folder upload failed", e)
            false
        }
    }

    private fun requestGameFolderAccess(onGranted: () -> Unit) {
        val pkg = gamePackage ?: return
        pendingAction = onGranted
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        try {
            val initialUri =
                "content://com.android.externalstorage.documents/document/primary:Android/data/$pkg".toUri()
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
        } catch (_: Exception) {}
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        gameFolderPickerLauncher.launch(intent)
        showToast(getString(R.string.custom_upload_need_folder_access, pkg))
    }

    private fun getGameTreeUri(): Uri? {
        val saved = prefs.getString(getPrefsKey(), null) ?: return null
        return saved.toUri()
    }

    private fun getPrefsKey(): String = "saf_tree_uri_${gamePackage}"

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
                val target = targetDir.createFile(child.type ?: "application/octet-stream", name) ?: continue
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
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index != -1 && it.moveToFirst()) it.getString(index) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun setLoading(loading: Boolean) {
        with(binding) {
            uploadProgress.visibility = if (loading) View.VISIBLE else View.GONE
            btnSelectFile.isEnabled = !loading
            btnSelectFolder.isEnabled = !loading
            btnUploadFile.isEnabled = !loading
            btnUploadFolder.isEnabled = !loading
            btnClearSelection.isEnabled = !loading
        }
    }

    private fun showToast(message: String) {
        runOnUiThread { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }

    private suspend fun execShizukuCommand(command: Array<String>): Int = suspendCancellableCoroutine { cont ->
        val args = UserServiceArgs(
            ComponentName(packageName, com.catsmoker.app.services.FileService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("file_service")
            .debuggable(BuildConfig.DEBUG)
            .version(1)

        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: android.os.IBinder?) {
                try {
                    val fileService = IFileService.Stub.asInterface(service)
                    val result = fileService.executeCommand(command)
                    if (cont.isActive) cont.resume(result)
                } catch (e: Exception) {
                    Log.e(TAG, "Shizuku command failed", e)
                    if (cont.isActive) cont.resume(-1)
                } finally {
                    try {
                        Shizuku.unbindUserService(args, this, true)
                    } catch (_: Exception) {}
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }

        Shizuku.bindUserService(args, connection)
    }

    companion object {
        private const val TAG = "CustomUploadActivity"
        private const val PREFS_NAME = "custom_upload_prefs"
        const val EXTRA_GAME_PACKAGE = "GAME_PACKAGE"
        const val EXTRA_GAME_NAME = "GAME_NAME"
    }
}
