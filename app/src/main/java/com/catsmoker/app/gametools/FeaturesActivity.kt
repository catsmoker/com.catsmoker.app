package com.catsmoker.app.gametools

import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.CompoundButton
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.catsmoker.app.BuildConfig
import com.catsmoker.app.IFileService
import com.catsmoker.app.R
import com.catsmoker.app.safeDismiss
import com.catsmoker.app.safeShow
import com.catsmoker.app.databinding.ActivityGameFeaturesScreenBinding
import com.catsmoker.app.main.setupScreenHeader
import com.catsmoker.app.spoofing.FileService
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.Calendar
import java.util.concurrent.TimeUnit

class FeaturesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameFeaturesScreenBinding
    private var gameAdapter: GameAdapter? = null
    private val gameList = ArrayList<GameInfo>()
    private val appPrefs by lazy { getSharedPreferences(APP_PREFS, MODE_PRIVATE) }
    private val dndFeature by lazy {
        DndFeature(
            context = this,
            requestPolicyAccess = {
                val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                notificationPolicyLauncher.launch(intent)
            },
            saveState = { enabled -> saveSwitchState(KEY_DND_ENABLED, enabled) },
            showMessage = { messageResId -> showSnackbar(getString(messageResId)) }
        )
    }
    private val cleaningFeature by lazy {
        CleaningFeature(
            activity = this,
            binding = binding,
            isRooted = { isRootedCached },
            isShizukuRunning = { Shizuku.pingBinder() },
            getFileService = { fileService },
            ensureShizukuConnected = { checkAndBindShizuku() },
            showSnackbar = { msg -> showSnackbar(msg) }
        )
    }
    private val dnsFeature by lazy {
        DnsFeature(
            activity = this,
            context = this,
            binding = binding,
            isRooted = { isRootedCached },
            showSnackbar = { msg -> showSnackbar(msg) }
        )
    }

    private var isRootedCached = false
    private var selectedScopeAssetName = DEFAULT_SCOPE_ASSET

    // Shizuku
    private var fileService: IFileService? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            fileService = IFileService.Stub.asInterface(service)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            fileService = null
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener { fileService = null }
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { checkAndBindShizuku() }
    private val requestPermissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                bindShizukuService()
            } else {
                showSnackbar(getString(R.string.shizuku_permission_denied))
            }
        }
    }

    // Permission Launchers
    private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>
    private lateinit var overlayPermissionLauncher: ActivityResultLauncher<Intent>
    private lateinit var usageStatsLauncher: ActivityResultLauncher<Intent>
    private lateinit var notificationPolicyLauncher: ActivityResultLauncher<Intent>

    private val crosshairServiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.action) {
                CrosshairOverlayService.ACTION_CROSSHAIR_SERVICE_STARTED -> {
                    binding.btnToggleCrosshair.isChecked = true
                    saveSwitchState(KEY_CROSSHAIR_ENABLED, true)
                }
                CrosshairOverlayService.ACTION_CROSSHAIR_SERVICE_STOPPED -> {
                    binding.btnToggleCrosshair.isChecked = false
                    saveSwitchState(KEY_CROSSHAIR_ENABLED, false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameFeaturesScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupScreenHeader(R.string.features_title, R.string.features_header_subtitle)

        // Init Root Shell (Async ideally, but Shell.getShell() caches)
        lifecycleScope.launch(Dispatchers.IO) { Shell.getShell() }

        initPermissionLaunchers()
        setupRecycler()
        loadSavedSettings()
        setupLogic()

        checkRootStatus()
        scanForGames()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })

        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener)

        val filter = IntentFilter().apply {
            addAction(CrosshairOverlayService.ACTION_CROSSHAIR_SERVICE_STARTED)
            addAction(CrosshairOverlayService.ACTION_CROSSHAIR_SERVICE_STOPPED)
        }
        ContextCompat.registerReceiver(this, crosshairServiceReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        checkAndBindShizuku()
    }

    private fun setupRecycler() {
        gameAdapter = GameAdapter(this, gameList)
        binding.gamesRecyclerView.layoutManager =
            LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.gamesRecyclerView.adapter = gameAdapter
    }

    private fun checkAndBindShizuku() {
        if (!Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            bindShizukuService()
        } else {
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        }
    }

    private fun bindShizukuService() {
        if (fileService != null) return
        try {
            val args = Shizuku.UserServiceArgs(ComponentName(this, FileService::class.java))
                .daemon(false)
                .processNameSuffix("file_service")
                .debuggable(BuildConfig.DEBUG)
                .version(BuildConfig.VERSION_CODE)
            Shizuku.bindUserService(args, serviceConnection)
        } catch (e: Exception) {
            showSnackbar(getString(R.string.shizuku_bind_failed, e.message))
        }
    }

    private fun initPermissionLaunchers() {
        vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startVpnServiceInternal()
                binding.vpnSwitch.isChecked = true
                saveSwitchState(KEY_VPN_ENABLED, true)
            } else {
                showSnackbar(getString(R.string.vpn_permission_denied))
                binding.vpnSwitch.isChecked = false
            }
        }

        overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (canDrawOverlays()) {
                showSnackbar(getString(R.string.overlay_permission_granted))
            } else {
                showSnackbar(getString(R.string.overlay_permission_required))
                binding.btnToggleOverlay.isChecked = false
                binding.btnToggleCrosshair.isChecked = false
            }
        }

        usageStatsLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
        notificationPolicyLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    }

    private fun loadSavedSettings() {
        binding.btnToggleOverlay.isChecked = appPrefs.getBoolean(KEY_OVERLAY_ENABLED, false)

        val crosshairEnabled = appPrefs.getBoolean(KEY_CROSSHAIR_ENABLED, false)
        binding.btnToggleCrosshair.isChecked = crosshairEnabled
        binding.crosshairStylePicker.visibility = if (crosshairEnabled) View.VISIBLE else View.GONE

        selectedScopeAssetName = appPrefs.getString(KEY_SELECTED_SCOPE, DEFAULT_SCOPE_ASSET) ?: DEFAULT_SCOPE_ASSET

        binding.dndSwitch.isChecked = appPrefs.getBoolean(KEY_DND_ENABLED, false)
        binding.playTimeSwitch.isChecked = appPrefs.getBoolean(KEY_PLAY_TIME_ENABLED, false)
        binding.vpnSwitch.isChecked = appPrefs.getBoolean(KEY_VPN_ENABLED, false)
    }

    private fun saveSwitchState(key: String, value: Boolean) {
        appPrefs.edit { putBoolean(key, value) }
    }

    private fun setupLogic() {
        setupDndSwitch()
        setupPlayTimeSwitch()
        setupVpnSwitch()
        setupOverlayFeature()
        setupCrosshairFeature()
        setupDnsFeature()
        setupScopeSelection()
        setupCleanButtons()
        setupViewAllButton()
        setupResolutionChangerButton()
        setupExpandableSections()
    }

    private fun setupViewAllButton() {
        binding.btnViewAll.setOnClickListener { showAppSelectionDialog() }
    }

    private fun setupResolutionChangerButton() {
        binding.btnResolutionChanger.setOnClickListener {
            startActivity(Intent(this, ResolutionChangerActivity::class.java))
        }
    }

    private fun showAppSelectionDialog() {
        lifecycleScope.launch(Dispatchers.Default) {
            val pm = packageManager
            val activities = queryLauncherActivities(pm).sortedWith { a, b ->
                a.loadLabel(pm).toString().compareTo(b.loadLabel(pm).toString(), ignoreCase = true)
            }

            val appNames = activities.map { it.loadLabel(pm).toString() }.toTypedArray()
            val packageNames = activities.map { it.activityInfo.packageName }

            // "Manual games" = non-game apps the user wants to include.
            // "Hidden games" = apps (including detected games) the user wants to exclude from the library.
            val manualGames = getManualGames()
            val hiddenGames = getHiddenGames()

            val checkedItems = BooleanArray(activities.size) { i ->
                val pkg = packageNames[i]
                val ai = activities[i].activityInfo.applicationInfo
                val detectedGame = isGame(ai)
                val visibleByDefault = detectedGame || manualGames.contains(pkg)
                visibleByDefault && !hiddenGames.contains(pkg)
            }

            withContext(Dispatchers.Main) {
                val dialog = AlertDialog.Builder(this@FeaturesActivity)
                    .setTitle(R.string.select_games)
                    .setMultiChoiceItems(appNames, checkedItems) { _, which, isChecked ->
                        val pkg = packageNames[which]
                        val ai = activities[which].activityInfo.applicationInfo
                        val detectedGame = isGame(ai)

                        if (isChecked) {
                            // Make visible: unhide it. Only add to manual list if it isn't a detected game.
                            hiddenGames.remove(pkg)
                            if (!detectedGame) manualGames.add(pkg) else manualGames.remove(pkg)
                        } else {
                            // Hide: always remove manual include; for detected games also add to hidden.
                            manualGames.remove(pkg)
                            if (detectedGame) hiddenGames.add(pkg)
                        }

                        saveManualGames(manualGames)
                        saveHiddenGames(hiddenGames)
                    }
                    .setPositiveButton(R.string.done, null)
                    .create()

                if (dialog.safeShow("FeaturesActivity")) {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setOnClickListener {
                        scanForGames()
                        dialog.safeDismiss("FeaturesActivity")
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncUIWithSystemState()
        scanForGames()
    }

    private fun syncUIWithSystemState() {
        dndFeature.sync(binding.dndSwitch)

        binding.btnToggleOverlay.setOnCheckedChangeListener(null)
        val isOverlayRunning = PerformanceOverlayService.isRunning && canDrawOverlays()
        binding.btnToggleOverlay.isChecked = isOverlayRunning
        saveSwitchState(KEY_OVERLAY_ENABLED, isOverlayRunning)
        setupOverlayFeature()

        binding.btnToggleCrosshair.setOnCheckedChangeListener(null)
        val isCrosshairRunning = CrosshairOverlayService.isRunning && canDrawOverlays()
        binding.btnToggleCrosshair.isChecked = isCrosshairRunning
        saveSwitchState(KEY_CROSSHAIR_ENABLED, isCrosshairRunning)
        setupCrosshairFeature()

        binding.playTimeSwitch.setOnCheckedChangeListener(null)
        val hasPerm = hasUsageStatsPermission()
        binding.playTimeSwitch.isChecked = binding.playTimeSwitch.isChecked && hasPerm
        setupPlayTimeSwitch()

        binding.vpnSwitch.setOnCheckedChangeListener(null)
        val isVpnRunning = GameVpnService.isRunning
        binding.vpnSwitch.isChecked = isVpnRunning
        saveSwitchState(KEY_VPN_ENABLED, isVpnRunning)
        setupVpnSwitch()
    }

    private fun setupOverlayFeature() {
        binding.btnToggleOverlay.setOnCheckedChangeListener { buttonView, isChecked ->
            saveSwitchState(KEY_OVERLAY_ENABLED, isChecked)
            if (isChecked) {
                ifOverlayPermissionGranted(buttonView) { togglePerformanceOverlayService(true) }
            } else {
                togglePerformanceOverlayService(false)
            }
        }
    }

    private fun setupCrosshairFeature() {
        binding.btnToggleCrosshair.setOnCheckedChangeListener { buttonView, isChecked ->
            saveSwitchState(KEY_CROSSHAIR_ENABLED, isChecked)
            binding.crosshairStylePicker.isVisible = isChecked
            if (isChecked) {
                ifOverlayPermissionGranted(buttonView) { startCrosshairService() }
            } else {
                stopService(Intent(this, CrosshairOverlayService::class.java))
            }
        }
    }

    private inline fun ifOverlayPermissionGranted(buttonView: View, onGranted: () -> Unit) {
        if (!canDrawOverlays()) {
            (buttonView as? CompoundButton)?.isChecked = false
            requestOverlayPermission()
            return
        }
        onGranted()
    }

    private fun togglePerformanceOverlayService(enable: Boolean) {
        val intent = Intent(this, PerformanceOverlayService::class.java)
        if (enable) startForegroundService(intent) else stopService(intent)
    }

    private fun startCrosshairService() {
        val serviceIntent = Intent(this, CrosshairOverlayService::class.java)
        serviceIntent.putExtra(CrosshairOverlayService.EXTRA_SCOPE_ASSET_NAME, selectedScopeAssetName)
        startForegroundService(serviceIntent)
    }

    private fun setupVpnSwitch() {
        binding.vpnSwitch.setOnCheckedChangeListener { _, isChecked ->
            saveSwitchState(KEY_VPN_ENABLED, isChecked)
            if (isChecked) {
                val vpnIntent = VpnService.prepare(this)
                if (vpnIntent != null) {
                    vpnPermissionLauncher.launch(vpnIntent)
                } else {
                    startVpnServiceInternal()
                }
            } else {
                val intent = Intent(this, GameVpnService::class.java)
                intent.action = GameVpnService.ACTION_DISCONNECT
                startService(intent)
            }
        }
    }

    private fun startVpnServiceInternal() {
        val intent = Intent(this, GameVpnService::class.java)
        intent.action = GameVpnService.ACTION_CONNECT
        startForegroundService(intent)
    }

    // --- DNS Logic ---
    private fun setupDnsFeature() {
        dnsFeature.setupDnsFeature()
    }

    private fun setupDndSwitch() {
        dndFeature.bind(binding.dndSwitch)
    }

    private fun setupPlayTimeSwitch() {
        binding.playTimeSwitch.setOnCheckedChangeListener { view, isChecked ->
            saveSwitchState(KEY_PLAY_TIME_ENABLED, isChecked)
            if (isChecked) {
                if (!hasUsageStatsPermission()) {
                    view.isChecked = false
                    val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                    usageStatsLauncher.launch(intent)
                } else {
                    scanForGames()
                }
            } else {
                scanForGames()
            }
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        val intent =
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
        overlayPermissionLauncher.launch(intent)
    }

    private fun checkRootStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            val rooted = try { Shell.cmd("id").exec().isSuccess } catch (_: Exception) { false }
            withContext(Dispatchers.Main) {
                isRootedCached = rooted
                dnsFeature.onRootStatusChanged(rooted)
                cleaningFeature.updateCleanUI(rooted)
            }
        }
    }

    private fun scanForGames() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val activities = queryLauncherActivities(pm)
            val newGames = mutableListOf<GameInfo>()

            val manuallyAdded = getManualGames()
            val hiddenGames = getHiddenGames()

            val trackingEnabled = binding.playTimeSwitch.isChecked && hasUsageStatsPermission()
            val statsMap = if (trackingEnabled) getAppUsageStats() else null

            for (ri in activities) {
                val ai = ri.activityInfo.applicationInfo
                val pkg = ai.packageName
                val include = (isGame(ai) || manuallyAdded.contains(pkg)) && !hiddenGames.contains(pkg)
                if (include) {
                    var formattedTime: String? = null
                    if (trackingEnabled && statsMap != null) {
                        val stats = statsMap[pkg]
                        formattedTime = if (stats != null) {
                            formatDuration(stats.totalTimeInForeground)
                        } else {
                            getString(R.string.duration_m, 0)
                        }
                    }

                    newGames.add(
                        GameInfo(
                            ai.loadLabel(pm).toString(),
                            pkg,
                            ai.loadIcon(pm),
                            formattedTime
                        )
                    )
                }
            }
            withContext(Dispatchers.Main) {
                gameList.clear()
                gameList.addAll(newGames)
                gameAdapter?.notifyDataSetChanged()
            }
        }
    }

    private fun getAppUsageStats(): Map<String, UsageStats> {
        val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
        val calendar = Calendar.getInstance()
        val endTime = calendar.timeInMillis
        calendar.add(Calendar.YEAR, -1)
        val startTime = calendar.timeInMillis
        return usm.queryAndAggregateUsageStats(startTime, endTime)
    }

    private fun formatDuration(millis: Long): String {
        if (millis < 60000) return getString(R.string.duration_start)
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        return if (hours > 0) {
            getString(R.string.duration_h_m, hours, minutes)
        } else {
            getString(R.string.duration_m, minutes)
        }
    }

    private fun isGame(appInfo: ApplicationInfo): Boolean {
        if ((appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return false
        return appInfo.category == ApplicationInfo.CATEGORY_GAME
    }

    private fun setupScopeSelection() {
        val scopeAssets = (assets.list(CROSSHAIR_ASSETS_DIR) ?: emptyArray())
            .filter { it.startsWith("scope") && it.endsWith(".png") }
            .sorted()

        binding.crosshairChipGroup.removeAllViews()
        for (i in scopeAssets.indices) {
            val assetName = scopeAssets[i]
            val chip = Chip(this).apply {
                id = View.generateViewId()
                tag = assetName
                text = getString(R.string.scope_n, i + 1)
                isCheckable = true
                loadScopeDrawable(assetName)?.let { chipDrawable ->
                    chipIcon = chipDrawable
                }
                if (assetName == selectedScopeAssetName) isChecked = true
            }
            binding.crosshairChipGroup.addView(chip)
        }

        binding.crosshairChipGroup.setOnCheckedStateChangeListener { group, checkedIds ->
            if (checkedIds.isNotEmpty()) {
                val chip = group.findViewById<Chip>(checkedIds[0])
                if (chip != null) {
                    val assetName = chip.tag as? String ?: DEFAULT_SCOPE_ASSET
                    appPrefs.edit { putString(KEY_SELECTED_SCOPE, assetName) }
                    selectScope(assetName)
                }
            }
        }
    }

    private fun loadScopeDrawable(assetName: String): Drawable? {
        return try {
            assets.open("$CROSSHAIR_ASSETS_DIR/$assetName").use { input ->
                Drawable.createFromStream(input, assetName)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun selectScope(assetName: String) {
        selectedScopeAssetName = assetName
        if (CrosshairOverlayService.isRunning) {
            startCrosshairService()
        }
    }

    private fun setupCleanButtons() {
        cleaningFeature.setupCleanButtons()
    }

    private fun queryLauncherActivities(pm: PackageManager): List<ResolveInfo> {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        return pm.queryIntentActivities(mainIntent, 0)
    }

    private fun getManualGames(): HashSet<String> {
        return HashSet(appPrefs.getStringSet(KEY_MANUAL_GAMES, emptySet()) ?: emptySet())
    }

    private fun saveManualGames(values: Set<String>) {
        appPrefs.edit { putStringSet(KEY_MANUAL_GAMES, HashSet(values)) }
    }

    private fun getHiddenGames(): HashSet<String> {
        return HashSet(appPrefs.getStringSet(KEY_HIDDEN_GAMES, emptySet()) ?: emptySet())
    }

    private fun saveHiddenGames(values: Set<String>) {
        appPrefs.edit { putStringSet(KEY_HIDDEN_GAMES, HashSet(values)) }
    }

    private fun setupExpandableSections() {
        bindExpandableSection(binding.systemCardHeader, binding.systemCardContent, binding.systemExpandIcon)
        bindExpandableSection(binding.dnsCardHeader, binding.dnsCardContent, binding.dnsExpandIcon)
    }

    private fun bindExpandableSection(header: View, content: View, icon: View) {
        header.setOnClickListener {
            val expanded = !content.isVisible
            content.isVisible = expanded
            icon.animate().rotation(if (expanded) 180f else 0f).duration = 200
        }
    }

    fun requestIgnoreBatteryOptimizationsWrapper() {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
        } catch (_: Exception) {
            showSnackbar(getString(R.string.cannot_open_battery_settings))
        }
    }

    private fun showSnackbar(msg: String) {
        Snackbar.make(binding.root, msg, Snackbar.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener)
        try {
            unregisterReceiver(crosshairServiceReceiver)
        } catch (_: IllegalArgumentException) {}
    }

    companion object {
        private const val APP_PREFS = "AppPrefs"
        const val DNS_PREFS = "DnsPrefs"
        const val KEY_CUSTOM_DNS = "custom_dns"
        const val KEY_DNS_METHOD = "dns_method"
        const val KEY_DNS_PROVIDER_INDEX = "dns_provider_index"
        private const val KEY_MANUAL_GAMES = "manual_games"
        private const val KEY_HIDDEN_GAMES = "hidden_games"
        private const val KEY_OVERLAY_ENABLED = "overlay_enabled"
        private const val KEY_CROSSHAIR_ENABLED = "crosshair_enabled"
        private const val KEY_DND_ENABLED = "dnd_enabled"
        private const val KEY_PLAY_TIME_ENABLED = "play_time_enabled"
        private const val KEY_VPN_ENABLED = "vpn_enabled"
        private const val KEY_SELECTED_SCOPE = "selected_scope"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
        private const val CROSSHAIR_ASSETS_DIR = "crosshair"
        private const val DEFAULT_SCOPE_ASSET = "scope2.png"

    }
}
