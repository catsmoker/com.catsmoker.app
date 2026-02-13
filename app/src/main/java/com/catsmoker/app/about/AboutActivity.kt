package com.catsmoker.app.about

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.catsmoker.app.BuildConfig
import com.catsmoker.app.R
import com.catsmoker.app.safeShow
import com.catsmoker.app.databinding.ActivityAboutScreenBinding
import com.catsmoker.app.main.setupScreenHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutScreenBinding
    private var iconClickCount = 0
    private val prefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupScreenHeader(R.string.about_header_title, R.string.about_header_subtitle)
        displayAppVersion()
        setupEasterEgg()
        setupAdsToggle()
        setupListeners()
    }

    private fun setupAdsToggle() {
        val enabled = prefs.getBoolean(KEY_ADS_ENABLED, true)
        binding.adsSwitch.isChecked = enabled
        binding.adsSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit { putBoolean(KEY_ADS_ENABLED, isChecked) }
        }
    }

    private fun setupListeners() {
        bindUrlButton(binding.donateButton, "https://catsmoker.vercel.app/#donate-section")
        bindUrlButton(binding.legalButton, "https://catsmoker.vercel.app/Legal")
        bindUrlButton(binding.githubButton, "https://github.com/catsmoker/com.catsmoker.app")
        binding.btnShizukuHelp.setOnClickListener { showShizukuHelpDialog() }
        bindUrlButton(binding.btnTelegram, "https://t.me/CATSM0KER")
        bindUrlButton(binding.btnYoutube, "https://www.youtube.com/@CATSMOKER")
        bindUrlButton(binding.btnPrivacyPolicy, "https://www.freeprivacypolicy.com/live/36fce55a-e1f4-456c-a828-1b058664698a")

        binding.checkForUpdatesButton.setOnClickListener {
            val isPreRelease = binding.releaseToggle.isChecked
            performUpdateCheck(isPreRelease)
        }
    }

    private fun bindUrlButton(button: View, url: String) {
        button.setOnClickListener { openUrl(url) }
    }

    private fun showShizukuHelpDialog() {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.shizuku_not_running_title)
            .setMessage(getString(R.string.shizuku_help_message))
            .setPositiveButton(R.string.open_shizuku) { _, _ ->
                try {
                    val intent = packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                    if (intent != null) startActivity(intent)
                    else showShizukuNotRunningToast()
                } catch (_: Exception) {
                    showShizukuNotRunningToast()
                }
            }
            .setNeutralButton(R.string.watch_tutorial) { _, _ ->
                openUrl("https://shizuku.rikka.app/")
            }
            .setNegativeButton(R.string.cancel_button, null)
            .create()

        dialog.safeShow(TAG)
    }

    private fun showShizukuNotRunningToast() {
        Toast.makeText(this, getString(R.string.shizuku_not_running), Toast.LENGTH_SHORT).show()
    }

    private fun openUrl(url: String) {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, url.toUri())
            startActivity(browserIntent)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.could_not_open_browser), Toast.LENGTH_SHORT).show()
        }
    }

    private fun performUpdateCheck(isPreRelease: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val releases = fetchReleases()
                val latestRelease = findLatestRelease(releases, isPreRelease)
                if (latestRelease != null) {
                    withContext(Dispatchers.Main) { processReleaseData(latestRelease) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Update check failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@AboutActivity,
                        getString(R.string.update_check_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    private fun findLatestRelease(releases: JSONArray, isPreRelease: Boolean): JSONObject? {
        if (releases.length() <= 0) return null
        for (i in 0 until releases.length()) {
            val release = releases.getJSONObject(i)
            if (release.getBoolean("prerelease") == isPreRelease) return release
        }
        return if (isPreRelease) releases.getJSONObject(0) else null
    }

    private fun fetchReleases(): JSONArray {
        val url = URL("https://api.github.com/repos/catsmoker/com.catsmoker.app/releases")
        val urlConnection = url.openConnection() as HttpURLConnection
        return try {
            urlConnection.connectTimeout = 5000
            urlConnection.readTimeout = 5000

            val response = urlConnection.inputStream.bufferedReader().use { it.readText() }
            JSONArray(response)
        } finally {
            urlConnection.disconnect()
        }
    }

    private fun processReleaseData(latestRelease: JSONObject) {
        try {
            val tagName = latestRelease.getString("tag_name")
            val htmlUrl = latestRelease.getString("html_url")

            val githubVersion = parseVersionFromTag(tagName)

            if (isUpdateAvailable(githubVersion)) {
                showUpdateDialog(tagName, htmlUrl)
            } else {
                Toast.makeText(this, getString(R.string.latest_version_msg), Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing release data", e)
        }
    }

    private fun showUpdateDialog(tagName: String?, downloadUrl: String?) {
        if (isFinishing) return

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(getString(R.string.update_available_msg, tagName))
            .setPositiveButton(R.string.update_button) { _, _ ->
                if (!downloadUrl.isNullOrBlank()) {
                    openUrl(downloadUrl)
                } else {
                    Toast.makeText(this, getString(R.string.could_not_open_browser), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.cancel_button, null)
            .create()

        dialog.safeShow(TAG)
    }

    private fun parseVersionFromTag(tagName: String?): String {
        if (tagName == null) return "0.0.0"
        val cleanTag = tagName.removePrefix("v")
        return cleanTag.substringAfter("-", cleanTag)
    }

    private fun displayAppVersion() {
        binding.tvVersion.text = getString(R.string.app_version_format, BuildConfig.VERSION_NAME)
    }

    private fun setupEasterEgg() {
        binding.ivAppIcon.setOnClickListener {
            iconClickCount++
            if (iconClickCount == 3) {
                Toast.makeText(this, getString(R.string.easter_egg_meow), Toast.LENGTH_SHORT).show()
                iconClickCount = 0
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressedDispatcher.onBackPressed()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun isUpdateAvailable(githubVersion: String): Boolean {
        val currentVersion = BuildConfig.VERSION_NAME
        try {
            val parts1 = githubVersion.split(".").filter { it.isNotEmpty() }
            val parts2 = currentVersion.split(".").filter { it.isNotEmpty() }

            val length = max(parts1.size, parts2.size)
            for (i in 0 until length) {
                val v1 = if (i < parts1.size) parts1[i].toInt() else 0
                val v2 = if (i < parts2.size) parts2[i].toInt() else 0

                if (v1 < v2) return false
                if (v1 > v2) return true
            }
        } catch (e: NumberFormatException) {
            Log.e(TAG, "Error comparing versions: $githubVersion vs $currentVersion", e)
            return false
        }
        return false
    }

    companion object {
        private const val TAG = "AboutActivity"
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_ADS_ENABLED = "ads_enabled"
    }
}
