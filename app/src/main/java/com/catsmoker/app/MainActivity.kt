package com.catsmoker.app

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
import androidx.lifecycle.lifecycleScope
import com.catsmoker.app.databinding.ActivityMainBinding
import com.catsmoker.app.databinding.ViewFlipperBinding
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

    private lateinit var binding: ActivityMainBinding
    private var statsJob: Job? = null
    private var isViewStubInflated = false

    // Ads
    private var interstitialAd: StartAppAd? = null
    private var isAdLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Check First Run
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        if (prefs.getBoolean("is_first_run", true)) {
            startActivity(Intent(this, WelcomeActivity::class.java))
            finish()
            return
        }

        // Check Permissions (only if not explicitly skipped)
        val permissionsSkipped = prefs.getBoolean("permissions_skipped", false)
        if (!permissionsSkipped && !arePermissionsGranted()) {
            startActivity(Intent(this, PermissionActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupButtons()

        // Defer heavier UI setup to avoid blocking first frame
        binding.root.post {
            initAds()
            setupViewStub()
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
        startActivity(Intent(this, FeaturesActivity::class.java))

        if (isAdLoaded) {
            interstitialAd?.showAd(object : AdDisplayListener {
                override fun adHidden(ad: Ad?) {
                    loadInterstitialAd()
                }

                override fun adDisplayed(ad: Ad?) {}
                override fun adClicked(ad: Ad?) {}
                override fun adNotDisplayed(ad: Ad?) {
                    loadInterstitialAd()
                }
            })
        } else {
            loadInterstitialAd()
        }
    }

    private fun setupButtons() {
        binding.btnRootLsposed.setOnClickListener {
            startActivity(Intent(this, RootActivity::class.java))
        }
        binding.btnShizuku.setOnClickListener {
            startActivity(Intent(this, NonRootActivity::class.java))
        }
        binding.btnAbout.setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }

        binding.btnWebsite.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, WEBSITE_URL.toUri()))
            } catch (e: Exception) {
                Log.e(TAG, "Browser not found", e)
            }
        }

        binding.btnCrosshair.setOnClickListener { openFeaturesThenShowAd() }
    }

    private fun showSupportedGamesDialog() {
        val builder = AlertDialog.Builder(
            this,
            com.google.android.material.R.style.Theme_Material3_DayNight_Dialog_Alert
        )

        val dialogView = layoutInflater.inflate(R.layout.supp_games, null)
        val searchView = dialogView.findViewById<SearchView>(R.id.search_view_games)
        val listView = dialogView.findViewById<ListView>(R.id.list_view_games)
        val btnOk = dialogView.findViewById<View>(R.id.btn_dialog_ok)

        val games = resources.getStringArray(R.array.supported_games)
        val adapter = ArrayAdapter(this, R.layout.list_item, games)
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
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, "market://search?q=$gameName".toUri()))
                } catch (_: ActivityNotFoundException) {
                    startActivity(Intent(Intent.ACTION_VIEW,
                        "https://play.google.com/store/search?q=$gameName".toUri()))
                }
            }
        }

        builder.setView(dialogView)
        val dialog = builder.create()

        btnOk?.setOnClickListener { dialog.dismiss() }

        dialog.show()

        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun setupViewStub() {
        if (isViewStubInflated) return

        binding.viewStubFlipper.setOnInflateListener { _, inflated ->
            isViewStubInflated = true
            val flipperBinding = ViewFlipperBinding.bind(inflated) // Use Binding for inflated view
            populateViewFlipper(flipperBinding)

            flipperBinding.flipperContainerLayout.setOnClickListener { showSupportedGamesDialog() }
        }
        binding.viewStubFlipper.inflate()
    }

    private fun populateViewFlipper(flipperBinding: ViewFlipperBinding) {
        val supportedGames = resources.getStringArray(R.array.supported_games)

        val typedValue = TypedValue()
        theme.resolveAttribute(androidx.appcompat.R.attr.colorAccent, typedValue, true)
        val accentColor = typedValue.data

        lifecycleScope.launch(Dispatchers.Main) {
            val batchSize = 12
            for (i in supportedGames.indices) {
                val textView = TextView(this@MainActivity)
                textView.text = supportedGames[i]
                textView.textSize = 18f
                textView.gravity = Gravity.CENTER
                textView.setTextColor(accentColor)
                textView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                flipperBinding.gameFlipper.addView(textView)

                if ((i + 1) % batchSize == 0) {
                    // Yield to keep the UI responsive during large lists
                    yield()
                }
            }
            flipperBinding.gameFlipper.startFlipping()
        }
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
        
        var level = 0
        var health = BatteryManager.BATTERY_HEALTH_UNKNOWN
        var temp = 0f

        if (batteryStatus != null) {
            level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            health = batteryStatus.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
            temp = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        }

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
        try {
            val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                packageName
            )
            if (mode != AppOpsManager.MODE_ALLOWED) return false
        } catch (_: Exception) {
            return false
        }

        // Check Storage (Android 11+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }

        return true
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val WEBSITE_URL = "https://catsmoker.vercel.app"
        private const val UPDATE_INTERVAL_MS = 2000L
    }
}
