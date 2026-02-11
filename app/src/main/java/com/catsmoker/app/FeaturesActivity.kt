package com.catsmoker.app

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.os.Process
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.catsmoker.app.databinding.ActivityGameFeaturesBinding
import com.catsmoker.app.services.CrosshairOverlayService
import com.catsmoker.app.services.FileService
import com.catsmoker.app.services.GameAdapter
import com.catsmoker.app.services.GameInfo
import com.catsmoker.app.services.GameVpnService
import com.catsmoker.app.services.PerformanceOverlayService
import com.google.android.material.chip.Chip
import com.google.android.material.snackbar.Snackbar
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

class FeaturesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGameFeaturesBinding
    private var gameAdapter: GameAdapter? = null
    private val gameList = ArrayList<GameInfo>()

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
                    saveSwitchState("crosshair_enabled", true)
                }
                CrosshairOverlayService.ACTION_CROSSHAIR_SERVICE_STOPPED -> {
                    binding.btnToggleCrosshair.isChecked = false
                    saveSwitchState("crosshair_enabled", false)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGameFeaturesBinding.inflate(layoutInflater)
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
            override fun handleOnBackPressed() {
                finish()
            }
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
        binding.gamesRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.gamesRecyclerView.adapter = gameAdapter
    }

    private fun checkAndBindShizuku() {
        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindShizukuService()
            } else {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
            }
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
        vpnPermissionLauncher = registerForActivityResult(StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                startVpnServiceInternal()
                binding.vpnSwitch.isChecked = true
                saveSwitchState("vpn_enabled", true)
            } else {
                showSnackbar(getString(R.string.vpn_permission_denied))
                binding.vpnSwitch.isChecked = false
            }
        }

        overlayPermissionLauncher = registerForActivityResult(StartActivityForResult()) {
            if (canDrawOverlays()) {
                showSnackbar(getString(R.string.overlay_permission_granted))
            } else {
                showSnackbar(getString(R.string.overlay_permission_required))
                binding.btnToggleOverlay.isChecked = false
                binding.btnToggleCrosshair.isChecked = false
            }
        }

        usageStatsLauncher = registerForActivityResult(StartActivityForResult()) {}
        notificationPolicyLauncher = registerForActivityResult(StartActivityForResult()) {}
    }

    private fun loadSavedSettings() {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)

        binding.btnToggleOverlay.isChecked = prefs.getBoolean("overlay_enabled", false)
        
        val crosshairEnabled = prefs.getBoolean("crosshair_enabled", false)
        binding.btnToggleCrosshair.isChecked = crosshairEnabled
        binding.crosshairStylePicker.visibility = if (crosshairEnabled) View.VISIBLE else View.GONE
        
        selectedScopeAssetName = prefs.getString("selected_scope", DEFAULT_SCOPE_ASSET) ?: DEFAULT_SCOPE_ASSET

        binding.dndSwitch.isChecked = prefs.getBoolean("dnd_enabled", false)
        binding.playTimeSwitch.isChecked = prefs.getBoolean("play_time_enabled", false)
        binding.vpnSwitch.isChecked = prefs.getBoolean("vpn_enabled", false)
    }

    private fun saveSwitchState(key: String, value: Boolean) {
        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit { putBoolean(key, value) }
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
        setupExpandableSections()
    }

    private fun setupViewAllButton() {
        binding.btnViewAll.setOnClickListener { showAppSelectionDialog() }
    }

    private fun showAppSelectionDialog() {
        lifecycleScope.launch(Dispatchers.Default) {
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val activities = pm.queryIntentActivities(mainIntent, 0)

            activities.sortWith { a, b ->
                a.loadLabel(pm).toString().compareTo(b.loadLabel(pm).toString(), ignoreCase = true)
            }

            val appNames = activities.map { it.loadLabel(pm).toString() }.toTypedArray()
            val packageNames = activities.map { it.activityInfo.packageName }

            val manuallyAdded = getSharedPreferences("AppPrefs", MODE_PRIVATE)
                .getStringSet("manual_games", HashSet()) ?: HashSet()

            val checkedItems = BooleanArray(activities.size) { i ->
                manuallyAdded.contains(packageNames[i])
            }

            withContext(Dispatchers.Main) {
                AlertDialog.Builder(this@FeaturesActivity)
                    .setTitle(R.string.select_games)
                    .setMultiChoiceItems(appNames, checkedItems) { _, which, isChecked ->
                        val pkg = packageNames[which]
                        val current = HashSet(getSharedPreferences("AppPrefs", MODE_PRIVATE)
                            .getStringSet("manual_games", HashSet()) ?: HashSet())
                        
                        if (isChecked) current.add(pkg) else current.remove(pkg)
                        
                        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit { putStringSet("manual_games", current) }
                    }
                    .setPositiveButton(R.string.done) { _, _ -> scanForGames() }
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        syncUIWithSystemState()
        scanForGames()
    }

    private fun syncUIWithSystemState() {
        // Detach listeners temporarily
        binding.dndSwitch.setOnCheckedChangeListener(null)
        val nm = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
        val dndActive = nm?.let { 
            it.getCurrentInterruptionFilter() != android.app.NotificationManager.INTERRUPTION_FILTER_ALL 
        } ?: false
        binding.dndSwitch.isChecked = dndActive
        saveSwitchState("dnd_enabled", dndActive)
        setupDndSwitch()

        binding.btnToggleOverlay.setOnCheckedChangeListener(null)
        val isOverlayRunning = PerformanceOverlayService.isRunning && canDrawOverlays()
        binding.btnToggleOverlay.isChecked = isOverlayRunning
        saveSwitchState("overlay_enabled", isOverlayRunning)
        setupOverlayFeature()

        binding.btnToggleCrosshair.setOnCheckedChangeListener(null)
        val isCrosshairRunning = CrosshairOverlayService.isRunning && canDrawOverlays()
        binding.btnToggleCrosshair.isChecked = isCrosshairRunning
        saveSwitchState("crosshair_enabled", isCrosshairRunning)
        setupCrosshairFeature()

        binding.playTimeSwitch.setOnCheckedChangeListener(null)
        val hasPerm = hasUsageStatsPermission()
        binding.playTimeSwitch.isChecked = binding.playTimeSwitch.isChecked && hasPerm
        setupPlayTimeSwitch()

        binding.vpnSwitch.setOnCheckedChangeListener(null)
        val isVpnRunning = isVpnServiceRunning
        binding.vpnSwitch.isChecked = isVpnRunning
        saveSwitchState("vpn_enabled", isVpnRunning)
        setupVpnSwitch()
    }

    private fun setupOverlayFeature() {
        binding.btnToggleOverlay.setOnCheckedChangeListener { buttonView, isChecked ->
            saveSwitchState("overlay_enabled", isChecked)
            if (isChecked) {
                if (!canDrawOverlays()) {
                    buttonView.isChecked = false
                    requestOverlayPermission()
                } else {
                    togglePerformanceOverlayService(true)
                }
            } else {
                togglePerformanceOverlayService(false)
            }
        }
    }

    private fun setupCrosshairFeature() {
        binding.btnToggleCrosshair.setOnCheckedChangeListener { buttonView, isChecked ->
            saveSwitchState("crosshair_enabled", isChecked)
            binding.crosshairStylePicker.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (isChecked) {
                if (!canDrawOverlays()) {
                    buttonView.isChecked = false
                    requestOverlayPermission()
                } else {
                    startCrosshairService()
                }
            } else {
                stopService(Intent(this, CrosshairOverlayService::class.java))
            }
        }
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
            saveSwitchState("vpn_enabled", isChecked)
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

    private val isVpnServiceRunning: Boolean
        get() {
            return GameVpnService.isRunning
        }

    // --- DNS Logic ---
    private fun setupDnsFeature() {
        val dnsPrefs = getSharedPreferences(DNS_PREFS, MODE_PRIVATE)
        binding.dnsEditText.setText(dnsPrefs.getString(KEY_CUSTOM_DNS, ""))

        var savedDnsMethodId = dnsPrefs.getInt(KEY_DNS_METHOD, R.id.radio_dns_root)
        if (savedDnsMethodId != R.id.radio_dns_root && savedDnsMethodId != R.id.radio_dns_vpn) {
            savedDnsMethodId = R.id.radio_dns_root
        }

        setupDnsSpinners(dnsPrefs)

        binding.dnsMethodRadioGroup.check(savedDnsMethodId)
        updateDnsUI()

        binding.dnsMethodRadioGroup.addOnButtonCheckedListener { _, _, isChecked ->
            if (isChecked) updateDnsUI()
        }

        binding.btnApplyDns.setOnClickListener { applyDnsChanges() }
    }

    private fun setupDnsSpinners(prefs: SharedPreferences) {
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, DNS_OPTIONS)
        binding.dnsSpinnerRoot.setAdapter(adapter)
        binding.dnsSpinnerVpn.setAdapter(adapter)

        val savedIndex = prefs.getInt(KEY_DNS_PROVIDER_INDEX, 0)
        if (savedIndex < DNS_OPTIONS.size) {
            val selectedText = DNS_OPTIONS[savedIndex]
            binding.dnsSpinnerRoot.setText(selectedText, false)
            binding.dnsSpinnerVpn.setText(selectedText, false)
        }

        binding.dnsSpinnerRoot.setOnItemClickListener { _, _, _, _ -> updateDnsUI() }
        binding.dnsSpinnerVpn.setOnItemClickListener { _, _, _, _ -> updateDnsUI() }
    }

    private fun updateDnsUI() {
        val checkedId = binding.dnsMethodRadioGroup.checkedButtonId
        val isRootMode = (checkedId == R.id.radio_dns_root)

        val activeSpinner = if (isRootMode) binding.dnsSpinnerRoot else binding.dnsSpinnerVpn
        val selectedOption = activeSpinner.text.toString()
        val isCustomSelected = selectedOption == "Custom"

        binding.rootDnsOptions.visibility = if (isRootMode) View.VISIBLE else View.GONE
        binding.vpnDnsTextInputLayout.visibility = if (isRootMode) View.GONE else View.VISIBLE
        binding.vpnCustomDnsLayout.visibility = if (isCustomSelected) View.VISIBLE else View.GONE
    }

    private fun getDnsOptionIndex(option: String): Int = DNS_OPTIONS.indexOf(option)

    private fun applyDnsChanges() {
        val dnsPrefs = getSharedPreferences(DNS_PREFS, MODE_PRIVATE)
        val methodId = binding.dnsMethodRadioGroup.checkedButtonId
        val editor = dnsPrefs.edit()

        editor.putInt(KEY_DNS_METHOD, methodId)
        val selectedIndex = if (methodId == R.id.radio_dns_root) {
            getDnsOptionIndex(binding.dnsSpinnerRoot.text.toString())
        } else {
            getDnsOptionIndex(binding.dnsSpinnerVpn.text.toString())
        }
        editor.putInt(KEY_DNS_PROVIDER_INDEX, selectedIndex)

        if (methodId == R.id.radio_dns_root) {
            applyRootDns()
            editor.apply()
        } else {
            applyVpnDns(editor)
            showSnackbar(getString(R.string.dns_saved_vpn))
        }
    }

    private fun applyRootDns() {
        if (!isRootedCached) {
            showSnackbar(getString(R.string.root_access_not_detected))
            return
        }

        val selected = binding.dnsSpinnerRoot.text.toString()
        val cmd = when {
            selected.contains("Google") -> "setprop net.dns1 8.8.8.8; setprop net.dns2 8.8.4.4"
            selected.contains("Cloudflare") -> "setprop net.dns1 1.1.1.1; setprop net.dns2 1.0.0.1"
            selected == "Custom" -> {
                val customIp = binding.dnsEditText.text?.toString()?.trim() ?: ""
                if (customIp.isEmpty()) {
                    showSnackbar(getString(R.string.please_enter_valid_ip))
                    return
                }
                val ips = customIp.split(",").filter { it.isNotBlank() }
                val dns1 = ips.getOrNull(0)?.trim() ?: ""
                val dns2 = ips.getOrNull(1)?.trim() ?: ""
                "setprop net.dns1 $dns1; setprop net.dns2 $dns2"
            }
            else -> "setprop net.dns1 \"\"; setprop net.dns2 \"\""
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val success = try { Shell.cmd(cmd).exec().isSuccess } catch (_: Exception) { false }
            withContext(Dispatchers.Main) {
                showSnackbar(
                    if (success) getString(R.string.dns_applied_root) 
                    else getString(R.string.root_command_failed)
                )
            }
        }
    }

    private fun applyVpnDns(editor: SharedPreferences.Editor) {
        val selected = binding.dnsSpinnerVpn.text.toString()
        val dnsToSave = when {
            selected == "Custom" -> binding.dnsEditText.text.toString()
            selected.contains("Google") -> "8.8.8.8,8.8.4.4"
            selected.contains("Cloudflare") -> "1.1.1.1,1.0.0.1"
            else -> ""
        }
        editor.putString(KEY_CUSTOM_DNS, dnsToSave)
        editor.apply()
    }

    private fun setupDndSwitch() {
        binding.dndSwitch.setOnCheckedChangeListener { view, isChecked ->
            saveSwitchState("dnd_enabled", isChecked)
            if (isChecked) {
                if (isNotificationPolicyAccessDenied) {
                    view.isChecked = false
                    val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    notificationPolicyLauncher.launch(intent)
                } else {
                    setDndMode(true)
                }
            } else {
                setDndMode(false)
            }
        }
    }

    private fun setupPlayTimeSwitch() {
        binding.playTimeSwitch.setOnCheckedChangeListener { view, isChecked ->
            saveSwitchState("play_time_enabled", isChecked)
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

    private fun setDndMode(enable: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
        if (isNotificationPolicyAccessDenied) return

        if (enable) {
            nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            showSnackbar(getString(R.string.dnd_enabled))
        } else {
            nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
            showSnackbar(getString(R.string.dnd_disabled))
        }
    }

    private val isNotificationPolicyAccessDenied: Boolean
        get() {
            val nm = getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager
            return nm == null || !nm.isNotificationPolicyAccessGranted
        }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(this)

    private fun requestOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
        overlayPermissionLauncher.launch(intent)
    }

    private fun checkRootStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            val rooted = try { Shell.cmd("id").exec().isSuccess } catch (_: Exception) { false }
            withContext(Dispatchers.Main) {
                isRootedCached = rooted
                val rootBtn = binding.dnsMethodRadioGroup.findViewById<View>(R.id.radio_dns_root)
                rootBtn.isEnabled = rooted
                if (!rooted && binding.dnsMethodRadioGroup.checkedButtonId == R.id.radio_dns_root) {
                    binding.dnsMethodRadioGroup.check(R.id.radio_dns_vpn)
                }
                updateCleanUI(rooted)
            }
        }
    }

    private fun updateCleanUI(rooted: Boolean) {
        val cleanMethods = if (rooted) {
            arrayOf(
                getString(R.string.clean_method_auto),
                getString(R.string.clean_method_root),
                getString(R.string.clean_method_shizuku),
                getString(R.string.clean_method_default)
            )
        } else {
            arrayOf(
                getString(R.string.clean_method_auto),
                getString(R.string.clean_method_shizuku),
                getString(R.string.clean_method_default)
            )
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, cleanMethods)
        binding.cleanMethodDropdown.setAdapter(adapter)
        if (binding.cleanMethodDropdown.text.isNullOrBlank()) {
            binding.cleanMethodDropdown.setText(cleanMethods[0], false)
        }

        if (rooted || Shizuku.pingBinder()) {
            binding.cleanSystemSummaryText.text = getString(R.string.clean_system_summary)
        } else {
            binding.cleanSystemSummaryText.text = getString(R.string.clean_system_summary_no_root)
        }
    }

    private fun scanForGames() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val activities = pm.queryIntentActivities(mainIntent, 0)
            val newGames = mutableListOf<GameInfo>()

            val manuallyAdded = getSharedPreferences("AppPrefs", MODE_PRIVATE)
                .getStringSet("manual_games", HashSet()) ?: HashSet()

            val trackingEnabled = binding.playTimeSwitch.isChecked && hasUsageStatsPermission()
            val statsMap = if (trackingEnabled) getAppUsageStats() else null

            for (ri in activities) {
                val ai = ri.activityInfo.applicationInfo
                if (isGame(ai) || manuallyAdded.contains(ai.packageName)) {
                    var formattedTime: String? = null
                    if (trackingEnabled && statsMap != null) {
                        val stats = statsMap[ai.packageName]
                            formattedTime = if (stats != null) {
                            formatDuration(stats.totalTimeInForeground)
                        } else {
                            getString(R.string.duration_m, 0)
                        }
                    }

                    newGames.add(
                        GameInfo(
                            ai.loadLabel(pm).toString(),
                            ai.packageName,
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

    private fun getAppUsageStats(): Map<String, android.app.usage.UsageStats> {
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
                    getSharedPreferences("AppPrefs", MODE_PRIVATE).edit { putString("selected_scope", assetName) }
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
        binding.btnCleanNow.setOnClickListener {
            when (binding.cleanMethodDropdown.text?.toString().orEmpty()) {
                getString(R.string.clean_method_root) -> {
                    if (isRootedCached) executeRootClean()
                    else showSnackbar(getString(R.string.root_access_not_detected))
                }
                getString(R.string.clean_method_shizuku) -> executeShizukuClean()
                getString(R.string.clean_method_default) -> executeNonRootClean()
                else -> {
                    if (isRootedCached) executeRootClean()
                    else if (Shizuku.pingBinder()) executeShizukuClean()
                    else executeNonRootClean()
                }
            }
        }
    }

    private fun setupExpandableSections() {
        binding.systemCardHeader.setOnClickListener {
            val isVisible = binding.systemCardContent.isVisible
            binding.systemCardContent.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.systemExpandIcon.animate().rotation(if (isVisible) 0f else 180f).duration = 200
        }

        binding.dnsCardHeader.setOnClickListener {
            val isVisible = binding.dnsCardContent.isVisible
            binding.dnsCardContent.visibility = if (isVisible) View.GONE else View.VISIBLE
            binding.dnsExpandIcon.animate().rotation(if (isVisible) 0f else 180f).duration = 200
        }
    }

    private fun executeRootClean() {
        binding.logTextView.text = getString(R.string.cleaning_start_root)
        lifecycleScope.launch(Dispatchers.IO) {
            val steps = arrayOf(
                getString(R.string.cleaning_step_cache),
                getString(R.string.cleaning_step_files),
                getString(R.string.cleaning_step_folders),
                getString(R.string.cleaning_step_hidden)
            )
            val commands = arrayOf(
                "find /data/user/0/*/cache -delete; find /data/user/0/*/code_cache -delete",
                "find /storage/emulated/0 -type f -size 0 -delete",
                "find /storage/emulated/0 -type d -empty -delete",
                "find /storage/emulated/0 -name '.*' -delete"
            )

            for (i in commands.indices) {
                val step = steps[i]
                val cmd = commands[i]
                withContext(Dispatchers.Main) { 
                    binding.logTextView.append(getString(R.string.cleaning_log_line, step)) 
                }
                Shell.cmd(cmd).exec()
            }
            withContext(Dispatchers.Main) {
                binding.logTextView.append(getString(R.string.cleaning_complete_root))
                showSnackbar(getString(R.string.root_cleaning_successful))
            }
        }
    }

    private fun executeShizukuClean() {
        if (fileService == null) {
            if (Shizuku.pingBinder()) {
                checkAndBindShizuku()
                showSnackbar(getString(R.string.connecting_to_shizuku))
            } else {
                showSnackbar(getString(R.string.shizuku_not_running))
            }
            return
        }

        binding.logTextView.text = getString(R.string.cleaning_start_shizuku)
        lifecycleScope.launch(Dispatchers.IO) {
            val steps = arrayOf(
                getString(R.string.cleaning_step_cache),
                getString(R.string.cleaning_step_files),
                getString(R.string.cleaning_step_folders),
                getString(R.string.cleaning_step_hidden)
            )
            val commands = arrayOf(
                "find /data/user/0/*/cache -delete; find /data/user/0/*/code_cache -delete",
                "find /storage/emulated/0 -type f -size 0 -delete",
                "find /storage/emulated/0 -type d -empty -delete",
                "find /storage/emulated/0 -name '.*' -delete"
            )
            try {
                for (i in commands.indices) {
                    val step = steps[i]
                    val cmd = commands[i]
                    withContext(Dispatchers.Main) { 
                    binding.logTextView.append(getString(R.string.cleaning_log_line, step)) 
                }
                    fileService?.executeCommand(arrayOf("sh", "-c", cmd))
                }
                withContext(Dispatchers.Main) {
                    binding.logTextView.append(getString(R.string.cleaning_complete_shizuku))
                    showSnackbar(getString(R.string.shizuku_cleaning_successful))
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { 
                    binding.logTextView.append(getString(R.string.cleaning_error, e.message)) 
                }
            }
        }
    }

    private fun executeNonRootClean() {
        binding.logTextView.text = getString(R.string.cleaning_start_default)
        try {
            val cacheDir = cacheDir
            if (cacheDir?.isDirectory == true) {
                deleteDir(cacheDir)
            }
            binding.logTextView.append(getString(R.string.cleaning_internal_cache_cleared))

            binding.root.postDelayed({
                showSnackbar(getString(R.string.storage_settings_opened))
                val intent = Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
                if (intent.resolveActivity(packageManager) != null) {
                    startActivity(intent)
                } else {
                    startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS))
                }
                binding.logTextView.append(getString(R.string.cleaning_process_finished))
            }, 1000)
        } catch (e: Exception) {
            binding.logTextView.append(getString(R.string.cleaning_error, e.message))
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
        const val DNS_PREFS = "DnsPrefs"
        const val KEY_CUSTOM_DNS = "custom_dns"
        const val KEY_DNS_METHOD = "dns_method"
        const val KEY_DNS_PROVIDER_INDEX = "dns_provider_index"
        private const val SHIZUKU_PERMISSION_REQUEST_CODE = 1001
        private const val CROSSHAIR_ASSETS_DIR = "crosshair"
        private const val DEFAULT_SCOPE_ASSET = "scope2.png"

        private val DNS_OPTIONS = arrayOf(
            "Default (DHCP)",
            "Custom",
            "Google (8.8.8.8)",
            "Cloudflare (1.1.1.1)"
        )

        fun deleteDir(dir: File?): Boolean {
            if (dir != null && dir.isDirectory) {
                val children = dir.list()
                children?.forEach { child ->
                    if (!deleteDir(File(dir, child))) return false
                }
                return dir.delete()
            } else if (dir != null && dir.isFile) {
                return dir.delete()
            }
            return false
        }
    }
}
