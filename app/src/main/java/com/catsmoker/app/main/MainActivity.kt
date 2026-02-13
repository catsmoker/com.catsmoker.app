package com.catsmoker.app.main

import android.app.ActivityManager
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.SearchView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.catsmoker.app.BuildConfig
import com.catsmoker.app.safeDismiss
import com.catsmoker.app.safeShow
import com.catsmoker.app.R
import com.catsmoker.app.about.AboutActivity
import com.catsmoker.app.databinding.ActivityMainScreenBinding
import com.catsmoker.app.gametools.FeaturesActivity
import com.catsmoker.app.spoofing.NonRootActivity
import com.catsmoker.app.spoofing.RootActivity
import com.startapp.sdk.ads.banner.Banner
import com.startapp.sdk.adsbase.Ad
import com.startapp.sdk.adsbase.StartAppAd
import com.startapp.sdk.adsbase.adlisteners.AdDisplayListener
import com.startapp.sdk.adsbase.adlisteners.AdEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainScreenBinding
    private var statsJob: Job? = null
    private val appPrefs by lazy { getSharedPreferences(PREFS_APP, MODE_PRIVATE) }

    // Ads
    private var interstitialAd: StartAppAd? = null
    private var isAdLoaded = false
    private val adsEnabled: Boolean
        get() = appPrefs.getBoolean(KEY_ADS_ENABLED, true)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Check First Run
        if (appPrefs.getBoolean(KEY_IS_FIRST_RUN, true)) return launchPermissionFlow()

        // Check Permissions (only if not explicitly skipped)
        val permissionsSkipped = appPrefs.getBoolean(KEY_PERMISSIONS_SKIPPED, false)
        if (!permissionsSkipped && !arePermissionsGranted()) return launchPermissionFlow()

        binding = ActivityMainScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupScreenHeader(R.string.app_name, R.string.main_header_subtitle, showBackButton = false)
        setupButtons()

        // Defer heavier UI setup to avoid blocking first frame
        binding.root.post {
            initAds()
            setupGameFlipper()
        }
    }

    override fun onResume() {
        super.onResume()
        startStatsMonitoring()
    }

    override fun onPause() {
        super.onPause()
        statsJob?.cancel()
    }

    // --- Ads Logic ---
    private fun initAds() {
        if (!adsEnabled) {
            binding.startioBanner.visibility = View.GONE
            isAdLoaded = false
            return
        }

        interstitialAd = StartAppAd(this)
        loadInterstitialAd()

        // In Activity onCreate, we are always in runtime, not design mode.
        // Replace the mock banner content with the real StartApp banner
        binding.startioBanner.removeAllViews()

        val realBanner = Banner(this)
        binding.startioBanner.addView(realBanner)
        realBanner.showBanner()
    }

    private fun loadInterstitialAd() {
        isAdLoaded = false
        interstitialAd?.loadAd(StartAppAd.AdMode.FULLPAGE, object : AdEventListener {
            override fun onReceiveAd(ad: Ad) {
                isAdLoaded = true
                Log.d(TAG, "Ad Loaded Successfully")
            }

            override fun onFailedToReceiveAd(ad: Ad?) {
                isAdLoaded = false
                Log.w(TAG, "Ad Load Failed: " + (ad?.errorMessage ?: "Unknown"))
            }
        })
    }

    private fun openFeaturesThenShowAd() {
        openScreen(FeaturesActivity::class.java)

        if (adsEnabled && isAdLoaded) {
            interstitialAd?.showAd(object : AdDisplayListener {
                override fun adHidden(ad: Ad?) {
                    reloadInterstitialAd()
                }

                override fun adDisplayed(ad: Ad?) {}
                override fun adClicked(ad: Ad?) {}
                override fun adNotDisplayed(ad: Ad?) {
                    reloadInterstitialAd()
                }
            })
        } else {
            reloadInterstitialAd()
        }
    }

    private fun setupButtons() {
        binding.btnSpoofing.setOnClickListener {
            binding.spoofingActions.isVisible = !binding.spoofingActions.isVisible
        }

        binding.btnRootLsposed.setOnClickListener { openScreen(RootActivity::class.java) }
        binding.btnShizuku.setOnClickListener { openScreen(NonRootActivity::class.java) }
        binding.btnAbout.setOnClickListener { openScreen(AboutActivity::class.java) }

        binding.btnCrosshair.setOnClickListener { openFeaturesThenShowAd() }
    }

    private fun showSupportedGamesDialog() {
        val builder = AlertDialog.Builder(
            this,
            com.google.android.material.R.style.Theme_Material3_DayNight_Dialog_Alert
        )

        val dialogView = layoutInflater.inflate(R.layout.dialog_supported_games, null)
        val searchView = dialogView.findViewById<SearchView>(R.id.search_view_games)
        val listView = dialogView.findViewById<ListView>(R.id.list_view_games)
        val btnOk = dialogView.findViewById<View>(R.id.btn_dialog_ok)

        val games = resources.getStringArray(R.array.supported_games)
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, games)
        listView.adapter = adapter

        searchView.setIconifiedByDefault(false)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                adapter.filter.filter(query)
                searchView.clearFocus()
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                adapter.filter.filter(newText)
                return true
            }
        })

        listView.setOnItemClickListener { _, _, position, _ ->
            val gameName = adapter.getItem(position)
            if (gameName != null) {
                openStoreSearch(gameName)
            }
        }

        builder.setView(dialogView)
        val dialog = builder.create()

        btnOk?.setOnClickListener { dialog.safeDismiss(TAG) }

        if (!dialog.safeShow(TAG)) return

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun setupGameFlipper() {
        binding.flipperContainerLayout.setOnClickListener { showSupportedGamesDialog() }

        val supportedGames = resources.getStringArray(R.array.supported_games)

        val typedValue = TypedValue()
        theme.resolveAttribute(androidx.appcompat.R.attr.colorAccent, typedValue, true)
        val accentColor = typedValue.data

        lifecycleScope.launch(Dispatchers.Main) {
            val batchSize = 12
            supportedGames.forEachIndexed { i, gameName ->
                val textView = TextView(this@MainActivity)
                textView.text = gameName
                textView.textSize = 18f
                textView.gravity = Gravity.CENTER
                textView.setTextColor(accentColor)
                textView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                binding.gameFlipper.addView(textView)

                if ((i + 1) % batchSize == 0) {
                    // Yield to keep the UI responsive during large lists
                    yield()
                }
            }
            binding.gameFlipper.startFlipping()
        }
    }

    private fun launchPermissionFlow() {
        startActivity(Intent(this, PermissionActivity::class.java))
        finish()
    }

    private fun reloadInterstitialAd() = loadInterstitialAd()

    private fun openStoreSearch(query: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, "market://search?q=$query".toUri()))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/search?q=$query".toUri()))
        }
    }

    private fun openScreen(activityClass: Class<out AppCompatActivity>) {
        startActivity(Intent(this, activityClass))
    }

    // --- Stats Monitoring (Coroutines) ---
    private fun startStatsMonitoring() {
        statsJob?.cancel()
        statsJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val stats = buildSystemInfoString()
                withContext(Dispatchers.Main) {
                    binding.appInfo.text = stats
                }
                delay(UPDATE_INTERVAL_MS)
            }
        }
    }

    private fun buildSystemInfoString(): String {
        val batteryStatus = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: 0
        val health = batteryStatus?.getIntExtra(
            BatteryManager.EXTRA_HEALTH,
            BatteryManager.BATTERY_HEALTH_UNKNOWN
        ) ?: BatteryManager.BATTERY_HEALTH_UNKNOWN
        val temp = (batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f

        return getString(
            R.string.app_info_format,
            BuildConfig.VERSION_NAME,
            System.getProperty("os.arch") ?: getString(R.string.not_available),
            Build.MODEL,
            memoryUsage,
            getHealthString(health),
            temp,
            level,
            networkUsage,
            diskUsage
        )
    }

    private fun getHealthString(healthInt: Int): String {
        return when (healthInt) {
            BatteryManager.BATTERY_HEALTH_GOOD -> getString(R.string.battery_good)
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> getString(R.string.battery_overheat)
            BatteryManager.BATTERY_HEALTH_DEAD -> getString(R.string.battery_dead)
            BatteryManager.BATTERY_HEALTH_COLD -> getString(R.string.battery_cold)
            else -> getString(R.string.battery_unknown)
        }
    }

    private val memoryUsage: String
        get() {
            val mi = ActivityManager.MemoryInfo()
            val activityManager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager
            return if (activityManager != null) {
                activityManager.getMemoryInfo(mi)
                val used = mi.totalMem - mi.availMem
                val percent = used.toFloat() / mi.totalMem * 100
                getString(R.string.percent_format, percent)
            } else {
                getString(R.string.not_available)
            }
        }

    private val diskUsage: String
        get() {
            return try {
                val path = filesDir
                val stat = StatFs(path.absolutePath)
                val total = stat.totalBytes
                val used = total - stat.availableBytes
                val percent = used.toFloat() / total * 100
                getString(R.string.percent_format, percent)
            } catch (_: Exception) {
                getString(R.string.not_available)
            }
        }

    private val networkUsage: String
        get() {
            val total = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
            return if (total < 0) getString(R.string.unsupported) else Formatter.formatFileSize(this, total)
        }

    private fun arePermissionsGranted(): Boolean {
        // Check Overlay
        if (!Settings.canDrawOverlays(this)) return false

        // Check Usage Stats
        val usageStatsGranted = try {
            val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
        if (!usageStatsGranted) return false

        // Check Storage (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }

        return true
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val UPDATE_INTERVAL_MS = 2000L
        private const val PREFS_APP = "app_prefs"
        private const val KEY_ADS_ENABLED = "ads_enabled"
        private const val KEY_IS_FIRST_RUN = "is_first_run"
        private const val KEY_PERMISSIONS_SKIPPED = "permissions_skipped"
    }
}
