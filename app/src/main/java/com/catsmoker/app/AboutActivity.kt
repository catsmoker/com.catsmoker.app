package com.catsmoker.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.catsmoker.app.databinding.ActivityAboutBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding
    private var iconClickCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupScreenHeader(R.string.about_header_title, R.string.about_header_subtitle)
        displayAppVersion()
        setupEasterEgg()
        setupListeners()
    }

    private fun setupListeners() {
        binding.donateButton.setOnClickListener { openUrl("https://catsmoker.vercel.app/#donate-section") }
        binding.legalButton.setOnClickListener { openUrl("https://catsmoker.vercel.app/Legal") }
        binding.githubButton.setOnClickListener { openUrl("https://github.com/catsmoker/com.catsmoker.app") }

        binding.checkForUpdatesButton.setOnClickListener {
            val isPreRelease = binding.releaseToggle.isChecked
            performUpdateCheck(isPreRelease)
        }
    }

    private fun openUrl(url: String?) {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, url?.toUri())
            startActivity(browserIntent)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.could_not_open_browser), Toast.LENGTH_SHORT).show()
        }
    }

    private fun performUpdateCheck(isPreRelease: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val releases = fetchReleases()
                if (releases.length() > 0) {
                    var latestRelease: JSONObject? = null

                    // Filter for pre-release vs stable
                    for (i in 0 until releases.length()) {
                        val release = releases.getJSONObject(i)
                        if (release.getBoolean("prerelease") == isPreRelease) {
                            latestRelease = release
                            break
                        }
                    }

                    // Fallback: If user wanted pre-release but none found, get latest stable
                    if (latestRelease == null && isPreRelease) {
                        latestRelease = releases.getJSONObject(0)
                    }

                    latestRelease?.let {
                        withContext(Dispatchers.Main) {
                            processReleaseData(it)
                        }
                    }
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

        AlertDialog.Builder(this)
            .setTitle(R.string.update_available_title)
            .setMessage(getString(R.string.update_available_msg, tagName))
            .setPositiveButton(R.string.update_button) { _, _ ->
                openUrl(downloadUrl)
            }
            .setNegativeButton(R.string.cancel_button, null)
            .show()
    }

    private fun parseVersionFromTag(tagName: String?): String {
        if (tagName == null) return "0.0.0"
        val cleanTag = if (tagName.startsWith("v")) tagName.substring(1) else tagName
        return if (cleanTag.contains("-")) {
            cleanTag.substring(cleanTag.indexOf("-") + 1)
        } else {
            cleanTag
        }
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
    }
}
