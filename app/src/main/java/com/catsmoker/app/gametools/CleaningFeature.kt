package com.catsmoker.app.gametools

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.catsmoker.app.IFileService
import com.catsmoker.app.R
import com.catsmoker.app.databinding.ActivityGameFeaturesScreenBinding
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class CleaningFeature(
    private val activity: AppCompatActivity,
    private val binding: ActivityGameFeaturesScreenBinding,
    private val isRooted: () -> Boolean,
    private val isShizukuRunning: () -> Boolean,
    private val getTargetPackages: () -> List<String>,
    private val getFileService: () -> IFileService?,
    private val ensureShizukuConnected: () -> Unit,
    private val showSnackbar: (String) -> Unit
) {
    private data class CleanMethod(val key: String, val label: String)

    private fun availableMethods(rooted: Boolean): List<CleanMethod> {
        // "Default" is intentionally app-only (safe). Root/Shizuku are library-targeted.
        val list = ArrayList<CleanMethod>(5)
        list.add(CleanMethod(METHOD_AUTO, activity.getString(R.string.clean_method_auto)))
        if (rooted) list.add(CleanMethod(METHOD_ROOT, activity.getString(R.string.clean_method_root)))
        list.add(CleanMethod(METHOD_SHIZUKU, activity.getString(R.string.clean_method_shizuku)))
        list.add(CleanMethod(METHOD_DEFAULT, activity.getString(R.string.clean_method_default)))
        list.add(CleanMethod(METHOD_DESTRUCTIVE, activity.getString(R.string.clean_method_destructive)))
        return list
    }


    fun setupCleanButtons() {
        binding.btnCleanNow.setOnClickListener {
            val rooted = isRooted()
            val methods = availableMethods(rooted)
            val selectedKey =
                (binding.cleanMethodDropdown.tag as? String)
                    ?: methods.firstOrNull { it.label == binding.cleanMethodDropdown.text?.toString().orEmpty() }?.key
                    ?: METHOD_AUTO

            when (selectedKey) {
                METHOD_ROOT -> {
                    if (!rooted) {
                        binding.logTextView.text = activity.getString(R.string.cleaning_error, activity.getString(R.string.root_access_not_detected))
                        showSnackbar(activity.getString(R.string.root_access_not_detected))
                        return@setOnClickListener
                    }
                    executeRootClean()
                }
                METHOD_SHIZUKU -> executeShizukuClean()
                METHOD_DEFAULT -> executeNonRootClean()
                METHOD_DESTRUCTIVE -> confirmAndExecuteDestructiveClean()
                else -> executeAutoClean()
            }
        }
    }

    private fun confirmAndExecuteDestructiveClean() {
        // This method is explicit by design. It runs deletions on shared storage for library apps only.
        // It is gated behind an "I understand" confirmation.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            showSnackbar(activity.getString(R.string.destructive_clean_requires_all_files))
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivity(intent)
            }
            return
        }

        val pkgs = getTargetPackages().distinct().filter { it.isNotBlank() }
        if (pkgs.isEmpty()) {
            binding.logTextView.text = activity.getString(R.string.cleaning_error, "No target apps found in library.")
            showSnackbar("No target apps found in library.")
            return
        }

        val paddingPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            18f,
            activity.resources.displayMetrics
        ).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val message = TextView(activity).apply { setText(R.string.destructive_clean_confirm_message) }
        val checkBox = CheckBox(activity).apply { setText(R.string.destructive_clean_confirm_checkbox) }
        container.addView(message)
        container.addView(checkBox)

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.destructive_clean_confirm_title)
            .setView(container)
            .setPositiveButton(R.string.destructive_clean_confirm_button, null)
            .setNegativeButton(R.string.cancel_button, null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.isEnabled = false
            checkBox.setOnCheckedChangeListener { _, isChecked ->
                positive.isEnabled = isChecked
            }
            positive.setOnClickListener {
                dialog.dismiss()
                executeDestructiveClean(pkgs)
            }
        }

        dialog.show()
    }

    private fun executeDestructiveClean(packages: List<String>) {
        val steps = listOf(
            activity.getString(R.string.cleaning_step_files),
            activity.getString(R.string.cleaning_step_folders),
            activity.getString(R.string.cleaning_step_hidden)
        )
        val commands = buildDestructiveSharedStorageCommands(packages)

        val fileService = getFileService()
        val executor: (String) -> Unit = when {
            isShizukuRunning() && fileService != null -> { cmd ->
                fileService.executeCommand(arrayOf("sh", "-c", cmd))
            }
            else -> { cmd ->
                // Can work without root if app has All Files Access.
                Shell.cmd(cmd).exec()
            }
        }

        runCleanSequence(
            startMessageRes = R.string.cleaning_start_destructive,
            doneMessageRes = R.string.cleaning_complete_destructive,
            successToastRes = R.string.destructive_cleaning_successful,
            steps = steps,
            commands = commands,
            executor = executor
        )
    }

    private fun executeAutoClean() {
        when {
            isRooted() -> executeRootClean()
            isShizukuRunning() -> executeShizukuClean()
            else -> executeNonRootClean()
        }
    }

    fun updateCleanUI(rooted: Boolean) {
        val methods = availableMethods(rooted)
        val labels = methods.map { it.label }
        val adapter = android.widget.ArrayAdapter(activity, android.R.layout.simple_dropdown_item_1line, labels)
        binding.cleanMethodDropdown.setAdapter(adapter)

        binding.cleanMethodDropdown.setOnItemClickListener { _, _, position, _ ->
            binding.cleanMethodDropdown.tag = methods.getOrNull(position)?.key
        }

        if (binding.cleanMethodDropdown.text.isNullOrBlank()) {
            binding.cleanMethodDropdown.setText(labels[0], false)
            binding.cleanMethodDropdown.tag = methods[0].key
        } else if (binding.cleanMethodDropdown.tag == null) {
            // Best-effort: restore tag from the current text.
            binding.cleanMethodDropdown.tag =
                methods.firstOrNull { it.label == binding.cleanMethodDropdown.text?.toString() }?.key
                    ?: METHOD_AUTO
        }

        if (rooted || isShizukuRunning()) {
            binding.cleanSystemSummaryText.text = activity.getString(R.string.clean_system_summary)
        } else {
            binding.cleanSystemSummaryText.text = activity.getString(R.string.clean_system_summary_no_root)
        }
    }

    private fun executeRootClean() {
        if (!isRooted()) {
            binding.logTextView.text = activity.getString(R.string.cleaning_error, activity.getString(R.string.root_access_not_detected))
            showSnackbar(activity.getString(R.string.root_access_not_detected))
            return
        }
        val pkgs = getTargetPackages().distinct().filter { it.isNotBlank() }
        if (pkgs.isEmpty()) {
            binding.logTextView.text = activity.getString(R.string.cleaning_error, "No target apps found in library.")
            showSnackbar("No target apps found in library.")
            return
        }

        val steps = listOf(
            activity.getString(R.string.cleaning_step_cache),
            "Clearing external cache for library apps..."
        )
        val commands = buildRootShizukuCommands(pkgs)

        runCleanSequence(
            startMessageRes = R.string.cleaning_start_root,
            doneMessageRes = R.string.cleaning_complete_root,
            successToastRes = R.string.root_cleaning_successful,
            steps = steps,
            commands = commands
        ) { cmd -> Shell.cmd(cmd).exec() }
    }

    private fun executeShizukuClean() {
        val fileService = getFileService()
        if (fileService == null) {
            if (isShizukuRunning()) {
                ensureShizukuConnected()
                showSnackbar(activity.getString(R.string.connecting_to_shizuku))
            } else {
                showSnackbar(activity.getString(R.string.shizuku_not_running))
            }
            return
        }

        val pkgs = getTargetPackages().distinct().filter { it.isNotBlank() }
        if (pkgs.isEmpty()) {
            binding.logTextView.text = activity.getString(R.string.cleaning_error, "No target apps found in library.")
            showSnackbar("No target apps found in library.")
            return
        }

        val steps = listOf(
            activity.getString(R.string.cleaning_step_cache),
            "Clearing external cache for library apps..."
        )
        val commands = buildRootShizukuCommands(pkgs)

        runCleanSequence(
            startMessageRes = R.string.cleaning_start_shizuku,
            doneMessageRes = R.string.cleaning_complete_shizuku,
            successToastRes = R.string.shizuku_cleaning_successful,
            steps = steps,
            commands = commands
        ) { cmd ->
            fileService.executeCommand(arrayOf("sh", "-c", cmd))
        }
    }

    private fun executeNonRootClean() {
        // Safe, app-only cleanup. No shell find on /storage/emulated/0 (which can be destructive with All Files access).
        binding.logTextView.text = activity.getString(R.string.cleaning_start_default)
        try {
            val cacheDir = activity.cacheDir
            if (cacheDir.isDirectory) {
                deleteDir(cacheDir)
            }
            activity.externalCacheDir?.let { ext ->
                if (ext.isDirectory) deleteDir(ext)
            }
            binding.logTextView.append(activity.getString(R.string.cleaning_internal_cache_cleared))

            binding.root.postDelayed({
                showSnackbar(activity.getString(R.string.storage_settings_opened))
                activity.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                binding.logTextView.append(activity.getString(R.string.cleaning_process_finished))
            }, 1000)
        } catch (e: Exception) {
            binding.logTextView.append(activity.getString(R.string.cleaning_error, e.message))
        }
    }

    private fun deleteDir(dir: File?): Boolean = when {
        dir == null -> false
        dir.isDirectory -> {
            dir.list()?.forEach { child ->
                if (!deleteDir(File(dir, child))) return false
            }
            dir.delete()
        }
        dir.isFile -> dir.delete()
        else -> false
    }

    private fun runCleanSequence(
        startMessageRes: Int,
        doneMessageRes: Int,
        successToastRes: Int,
        steps: List<String>,
        commands: List<String>,
        executor: (String) -> Unit
    ) {
        binding.logTextView.text = activity.getString(startMessageRes)
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                commands.forEachIndexed { index, cmd ->
                    val step = steps.getOrNull(index) ?: "Step ${index + 1}"
                    withContext(Dispatchers.Main) {
                        binding.logTextView.append(activity.getString(R.string.cleaning_log_line, step))
                    }
                    executor(cmd)
                }
                withContext(Dispatchers.Main) {
                    binding.logTextView.append(activity.getString(doneMessageRes))
                    showSnackbar(activity.getString(successToastRes))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.logTextView.append(activity.getString(R.string.cleaning_error, e.message))
                }
            }
        }
    }

    private fun buildRootShizukuCommands(packages: List<String>): List<String> {
        // Targeted cleanup: only caches for apps currently in the library list.
        // This replaces the previous extremely broad `/storage/emulated/0` find/delete operations.
        val pkgs = packages.joinToString(" ") { shQuote(it) }
        val internalCacheCmd = """
            for p in $pkgs; do
              rm -rf "/data/user/0/${'$'}p/cache/"* "/data/user/0/${'$'}p/code_cache/"* 2>/dev/null || true;
            done
        """.trimIndent()

        val externalCacheCmd = """
            for p in $pkgs; do
              rm -rf "/storage/emulated/0/Android/data/${'$'}p/cache/"* 2>/dev/null || true;
            done
        """.trimIndent()

        return listOf(internalCacheCmd, externalCacheCmd)
    }

    private fun buildDestructiveSharedStorageCommands(packages: List<String>): List<String> {
        // Scoped destructive cleanup: only under Android/data and Android/obb for library packages.
        val pkgs = packages.joinToString(" ") { shQuote(it) }

        val deleteEmptyFiles = """
            for p in $pkgs; do
              for base in "/storage/emulated/0/Android/data/${'$'}p" "/storage/emulated/0/Android/obb/${'$'}p"; do
                [ -d "${'$'}base" ] && find "${'$'}base" -type f -size 0 -delete 2>/dev/null || true;
              done
            done
        """.trimIndent()

        val deleteEmptyFolders = """
            for p in $pkgs; do
              for base in "/storage/emulated/0/Android/data/${'$'}p" "/storage/emulated/0/Android/obb/${'$'}p"; do
                [ -d "${'$'}base" ] && find "${'$'}base" -type d -empty -delete 2>/dev/null || true;
              done
            done
        """.trimIndent()

        val deleteHidden = """
            for p in $pkgs; do
              for base in "/storage/emulated/0/Android/data/${'$'}p" "/storage/emulated/0/Android/obb/${'$'}p"; do
                [ -d "${'$'}base" ] && find "${'$'}base" -name '.*' ! -name '.' ! -name '..' -delete 2>/dev/null || true;
              done
            done
        """.trimIndent()

        return listOf(deleteEmptyFiles, deleteEmptyFolders, deleteHidden)
    }

    private fun shQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        private const val METHOD_AUTO = "auto"
        private const val METHOD_ROOT = "root"
        private const val METHOD_SHIZUKU = "shizuku"
        private const val METHOD_DEFAULT = "default"
        private const val METHOD_DESTRUCTIVE = "destructive"
    }
}
