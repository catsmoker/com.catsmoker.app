package com.catsmoker.app.gametools

import android.content.Intent
import android.provider.Settings
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
    private val getFileService: () -> IFileService?,
    private val ensureShizukuConnected: () -> Unit,
    private val showSnackbar: (String) -> Unit
) {
    private val cleanSteps by lazy {
        arrayOf(
            activity.getString(R.string.cleaning_step_cache),
            activity.getString(R.string.cleaning_step_files),
            activity.getString(R.string.cleaning_step_folders),
            activity.getString(R.string.cleaning_step_hidden)
        )
    }

    private val cleanCommands = arrayOf(
        "find /data/user/0/*/cache -delete; find /data/user/0/*/code_cache -delete",
        "find /storage/emulated/0 -type f -size 0 -delete",
        "find /storage/emulated/0 -type d -empty -delete",
        "find /storage/emulated/0 -name '.*' -delete"
    )


    fun setupCleanButtons() {
        binding.btnCleanNow.setOnClickListener {
            val selectedMode = binding.cleanMethodDropdown.text?.toString().orEmpty()
            val rootMode = activity.getString(R.string.clean_method_root)
            val shizukuMode = activity.getString(R.string.clean_method_shizuku)
            val defaultMode = activity.getString(R.string.clean_method_default)

            when (selectedMode) {
                rootMode -> if (isRooted()) executeRootClean()
                    else showSnackbar(activity.getString(R.string.root_access_not_detected))
                shizukuMode -> executeShizukuClean()
                defaultMode -> executeNonRootClean()
                else -> executeAutoClean()
            }
        }
    }

    private fun executeAutoClean() {
        when {
            isRooted() -> executeRootClean()
            isShizukuRunning() -> executeShizukuClean()
            else -> executeNonRootClean()
        }
    }

    fun updateCleanUI(rooted: Boolean) {
        val cleanMethods = if (rooted) {
            arrayOf(
                activity.getString(R.string.clean_method_auto),
                activity.getString(R.string.clean_method_root),
                activity.getString(R.string.clean_method_shizuku),
                activity.getString(R.string.clean_method_default)
            )
        } else {
            arrayOf(
                activity.getString(R.string.clean_method_auto),
                activity.getString(R.string.clean_method_shizuku),
                activity.getString(R.string.clean_method_default)
            )
        }
        val adapter = android.widget.ArrayAdapter(
            activity,
            android.R.layout.simple_dropdown_item_1line,
            cleanMethods
        )
        binding.cleanMethodDropdown.setAdapter(adapter)
        if (binding.cleanMethodDropdown.text.isNullOrBlank()) {
            binding.cleanMethodDropdown.setText(cleanMethods[0], false)
        }

        if (rooted || isShizukuRunning()) {
            binding.cleanSystemSummaryText.text = activity.getString(R.string.clean_system_summary)
        } else {
            binding.cleanSystemSummaryText.text = activity.getString(R.string.clean_system_summary_no_root)
        }
    }

    private fun executeRootClean() {
        runCleanSequence(
            startMessageRes = R.string.cleaning_start_root,
            doneMessageRes = R.string.cleaning_complete_root,
            successToastRes = R.string.root_cleaning_successful
        ) { cmd ->
            Shell.cmd(cmd).exec()
        }
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

        runCleanSequence(
            startMessageRes = R.string.cleaning_start_shizuku,
            doneMessageRes = R.string.cleaning_complete_shizuku,
            successToastRes = R.string.shizuku_cleaning_successful
        ) { cmd ->
            fileService.executeCommand(arrayOf("sh", "-c", cmd))
        }
    }

    private fun executeNonRootClean() {
        binding.logTextView.text = activity.getString(R.string.cleaning_start_default)
        try {
            val cacheDir = activity.cacheDir
            if (cacheDir.isDirectory) {
                deleteDir(cacheDir)
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
        executor: (String) -> Unit
    ) {
        binding.logTextView.text = activity.getString(startMessageRes)
        activity.lifecycleScope.launch(Dispatchers.IO) {
            try {
                cleanCommands.forEachIndexed { index, cmd ->
                    val step = cleanSteps[index]
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
}
