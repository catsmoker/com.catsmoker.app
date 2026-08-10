package com.catsmoker.app.ui.activities

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.catsmoker.app.BuildConfig
import com.catsmoker.app.IFileService
import com.catsmoker.app.core.GamingEngine
import com.catsmoker.app.core.GamingModeState
import com.catsmoker.app.core.ShizukuManager
import com.catsmoker.app.core.DeviceDiagnosticManager
import com.catsmoker.app.data.model.GameInfo
import com.catsmoker.app.service.GameVpnService
import com.catsmoker.app.service.CrosshairOverlayService
import com.catsmoker.app.service.PerformanceOverlayService
import com.catsmoker.app.util.CleaningFeature
import com.catsmoker.app.util.FileService
import com.catsmoker.app.util.BoostController
import com.catsmoker.app.util.DeveloperOptionsController
import com.catsmoker.app.ui.screens.FeaturesScreen
import com.catsmoker.app.ui.screens.AppPickerDialog
import com.catsmoker.app.ui.theme.CatsmokerTheme
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.delay

class GamingToolsActivity : ComponentActivity() {

    private val appPrefs by lazy { getSharedPreferences("AppPrefs", MODE_PRIVATE) }
    private lateinit var shizukuManager: ShizukuManager
    private lateinit var deviceDiagnosticManager: DeviceDiagnosticManager
    private lateinit var gamingEngine: GamingEngine
    
    private var isRootedCached by mutableStateOf(false)
    private val gameList = mutableStateListOf<GameInfo>()
    private val allAppsList = mutableStateListOf<GameInfo>()
    private var isPickingGame by mutableStateOf(false)
    
    private var isOverlayRunning by mutableStateOf(false)
    private var isCrosshairRunning by mutableStateOf(false)
    private var selectedCrosshair by mutableStateOf("scope2.png")
    private var isVpnRunning by mutableStateOf(false)
    private var isDndEnabled by mutableStateOf(false)

    private var isBoostingRam by mutableStateOf(false)
    private var showRamResult by mutableStateOf(false)
    private var isOptimizingNet by mutableStateOf(false)
    private var showPingResult by mutableStateOf(false)
    private var isResettingDefaults by mutableStateOf(false)
    private var showResetResult by mutableStateOf(false)

    private var boostLevel by mutableStateOf(0)
    private lateinit var boostController: BoostController
    private lateinit var developerOptionsController: DeveloperOptionsController

    private val maintenanceLog = mutableStateListOf<String>()

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

    private lateinit var vpnPermissionLauncher: ActivityResultLauncher<Intent>
    private lateinit var overlayPermissionLauncher: ActivityResultLauncher<Intent>

    private val serviceStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            when (intent.action) {
                CrosshairOverlayService.ACTION_CROSSHAIR_SERVICE_STARTED -> isCrosshairRunning = true
                CrosshairOverlayService.ACTION_CROSSHAIR_SERVICE_STOPPED -> isCrosshairRunning = false
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        initPermissionLaunchers()

        shizukuManager = ShizukuManager(this)
        shizukuManager.init()
        deviceDiagnosticManager = DeviceDiagnosticManager(this)
        gamingEngine = GamingEngine(this, shizukuManager, deviceDiagnosticManager)

        selectedCrosshair = appPrefs.getString("selected_scope", "scope2.png") ?: "scope2.png"

        checkAudioPermission()

        Shizuku.addBinderReceivedListener(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        
        val filter = IntentFilter().apply {
            addAction(CrosshairOverlayService.ACTION_CROSSHAIR_SERVICE_STARTED)
            addAction(CrosshairOverlayService.ACTION_CROSSHAIR_SERVICE_STOPPED)
        }
        ContextCompat.registerReceiver(this, serviceStateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        checkRootStatus()
        checkAndBindShizuku()

        boostController = BoostController(this)
        boostLevel = appPrefs.getInt("boost_level", 0)
        boostController.applyBoost(boostLevel)

        developerOptionsController = DeveloperOptionsController(this, gamingEngine)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                GameVpnService.isRunningState.collect { running ->
                    isVpnRunning = running
                }
            }
        }

        setContent {
            CatsmokerTheme {
                val gamingState by gamingEngine.state.collectAsState()
                val isFixedPerformanceMode by gamingEngine.isFixedPerformanceMode.collectAsState()
                val boosterLog by gamingEngine.boosterLog.collectAsState()
                val boosterProgress by gamingEngine.boosterProgress.collectAsState()
                val animationScales by gamingEngine.animationScales.collectAsState()
                val alwaysFinishActivities by gamingEngine.alwaysFinishActivities.collectAsState()
                val backgroundProcessLimit by gamingEngine.backgroundProcessLimit.collectAsState()
                val angleSelectionsMap = remember { mutableStateMapOf<String, String>() }

                LaunchedEffect(Unit) {
                    angleSelectionsMap.putAll(developerOptionsController.getAngleDriverSelections())
                }

                FeaturesScreen(
                    gameList = gameList,
                    isRooted = isRootedCached,
                    isOverlayRunning = isOverlayRunning,
                    isCrosshairRunning = isCrosshairRunning,
                    selectedCrosshair = selectedCrosshair,
                    isVpnRunning = isVpnRunning,
                    isDndEnabled = isDndEnabled,
                    maintenanceLog = maintenanceLog,
                    gamingState = gamingState,
                    isFixedPerformanceMode = isFixedPerformanceMode,
                    isBoostingRam = isBoostingRam,
                    showRamResult = showRamResult,
                    isOptimizingNet = isOptimizingNet,
                    showPingResult = showPingResult,
                    isResettingDefaults = isResettingDefaults,
                    showResetResult = showResetResult,
                    boosterLog = boosterLog,
                    boosterProgress = boosterProgress,
                    boostLevel = boostLevel,
                    animationScales = animationScales,
                    alwaysFinishActivities = alwaysFinishActivities,
                    backgroundProcessLimit = backgroundProcessLimit,
                    angleSelections = angleSelectionsMap.toMap(),
                    onToggleOverlay = { toggleOverlay(it) },
                    onToggleCrosshair = { toggleCrosshair(it) },
                    onSelectCrosshair = { 
                        selectedCrosshair = it
                        appPrefs.edit().putString("selected_scope", it).apply()
                        if (isCrosshairRunning) startCrosshairService()
                    },
                    onToggleVpn = { toggleVpn(it) },
                    onToggleDnd = { toggleDnd(it) },
                    onPerformMaintenance = { deep -> 
                        if (deep) showDeepCleanAgreement()
                        else performMaintenance(false)
                    },
                    onActivateGamingMode = { 
                        lifecycleScope.launch { gamingEngine.toggleGamingMode(true) }
                    },
                    onDeactivateGamingMode = {
                        lifecycleScope.launch { gamingEngine.toggleGamingMode(false) }
                    },
                    onBoostRam = {
                        lifecycleScope.launch {
                            isBoostingRam = true
                            val (freed, stopped) = gamingEngine.manualBoostRam()
                            isBoostingRam = false
                            showRamResult = true
                            Toast.makeText(this@GamingToolsActivity, "Boosted! Freed $freed MB, stopped $stopped apps", Toast.LENGTH_SHORT).show()
                            delay(2000)
                            showRamResult = false
                        }
                    },
                    onCheckPing = {
                        lifecycleScope.launch {
                            isOptimizingNet = true
                            val pingRes = gamingEngine.measureNetworkLatency()
                            isOptimizingNet = false
                            showPingResult = true
                            val msg = if (pingRes != null) "Latency check complete: $pingRes ms" else "Latency check failed"
                            Toast.makeText(this@GamingToolsActivity, msg, Toast.LENGTH_SHORT).show()
                            delay(2000)
                            showPingResult = false
                        }
                    },
                    onResetDefaults = {
                        lifecycleScope.launch {
                            isResettingDefaults = true
                            val resetOk = gamingEngine.resetToDeviceDefaults()
                            isResettingDefaults = false
                            showResetResult = true
                            val msg = if (resetOk) "Device settings reset to OS defaults" else "Device reset partial"
                            Toast.makeText(this@GamingToolsActivity, msg, Toast.LENGTH_SHORT).show()
                            delay(2000)
                            showResetResult = false
                        }
                    },
                    onRunBooster = { mode, force ->
                        lifecycleScope.launch { gamingEngine.runArtOptimization(mode, force) }
                    },
                    onStopBooster = {
                        lifecycleScope.launch { gamingEngine.cancelArtOptimization() }
                    },
                    onToggleFixedPerformance = { enabled ->
                        lifecycleScope.launch { gamingEngine.toggleFixedPerformanceMode(enabled) }
                    },
                    onBoostChange = { level ->
                        boostLevel = level
                        appPrefs.edit().putInt("boost_level", level).apply()
                        boostController.applyBoost(level)
                    },
                    onSetAnimationScales = { w, t, a ->
                        lifecycleScope.launch { gamingEngine.setAnimationScales(w, t, a) }
                    },
                    onToggleAlwaysFinish = { enabled ->
                        lifecycleScope.launch { gamingEngine.toggleAlwaysFinishActivities(enabled) }
                    },
                    onToggleBackgroundLimit = { enabled ->
                        lifecycleScope.launch { gamingEngine.toggleBackgroundProcessLimit(enabled) }
                    },
                    onSetAngleDriver = { pkg, driver ->
                        lifecycleScope.launch {
                            developerOptionsController.setAngleDriverSelection(pkg, driver)
                            if (driver == null) angleSelectionsMap.remove(pkg) else angleSelectionsMap[pkg] = driver
                        }
                    },
                    onLaunchGame = { pkg ->
                        lifecycleScope.launch {
                            gamingEngine.toggleGamingMode(true, pkg)
                            val intent = packageManager.getLaunchIntentForPackage(pkg)
                            if (intent != null) startActivity(intent)
                        }
                    },
                    onRemoveGame = { pkg ->
                        removeGameFromLibrary(pkg)
                    },
                    onAddGameClicked = { 
                        isPickingGame = true
                        loadAllApps()
                    },
                    onBack = { finish() },
                    onSync = { syncState(); scanForGames() }
                )

                if (isPickingGame) {
                    AppPickerDialog(
                        apps = allAppsList,
                        onDismiss = { isPickingGame = false },
                        onAppSelected = { pkg: String ->
                            isPickingGame = false
                            addGameToLibrary(pkg)
                        }
                    )
                }
            }
        }
    }

    private fun showDeepCleanAgreement() {
        AlertDialog.Builder(this)
            .setTitle("Deep Cleaning Agreement")
            .setMessage("Deep Cleaning will remove:\n- Caches of ALL apps on your device\n- Hidden files and folders (starting with \".\")\n- Empty files and folders\n- Temporary data (*.tmp, *.temp)\n\nThis is a powerful operation that may log you out of some apps. Do you agree to proceed?")
            .setPositiveButton("I AGREE & CLEAN") { _, _ -> performMaintenance(true) }
            .setNegativeButton("CANCEL", null)
            .show()
    }

    private fun performMaintenance(deep: Boolean) {
        val context = this
        maintenanceLog.clear()
        logMaintenance("Starting system maintenance...")
        
        lifecycleScope.launch(Dispatchers.IO) {
            val hasRoot = isRootedCached
            val hasShizuku = fileService != null

            if (hasRoot) {
                logMaintenance("Executing ${if (deep) "Deep" else "Safe"} Clean (Root)...")
                val results = CleaningFeature.executeRootClean(deep)
                results.forEach { logMaintenance(it) }
                logMaintenance("System-wide cleaning completed via Root.")
            } else if (hasShizuku) {
                logMaintenance("Executing ${if (deep) "Deep" else "Safe"} Clean (Shizuku)...")
                val results = CleaningFeature.executeShizukuClean(fileService!!, deep)
                results.forEach { logMaintenance(it) }
                logMaintenance("System-wide cleaning completed via Shizuku.")
            } else {
                logMaintenance("Executing Local Clean...")
                CleaningFeature.executeNonRootClean(context)
                logMaintenance("App-local cache cleared. (Shizuku/Root required for full system clean)")
            }
            
            logMaintenance("Compacting background processes...")
            Shell.cmd("am compact background").exec()
            logMaintenance("Maintenance complete!")
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Maintenance Done", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun logMaintenance(m: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        maintenanceLog.add("[$time] $m")
    }

    private fun syncState() {
        isOverlayRunning = PerformanceOverlayService.isRunning && Settings.canDrawOverlays(this)
        isCrosshairRunning = CrosshairOverlayService.isRunning && Settings.canDrawOverlays(this)
        isVpnRunning = GameVpnService.isRunning
        
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        isDndEnabled = nm.currentInterruptionFilter != android.app.NotificationManager.INTERRUPTION_FILTER_ALL
    }

    private fun toggleOverlay(enable: Boolean) {
        if (enable && !Settings.canDrawOverlays(this)) { requestOverlayPermission(); return }
        val intent = Intent(this, PerformanceOverlayService::class.java)
        if (enable) startForegroundService(intent) else stopService(intent)
        isOverlayRunning = enable
    }

    private fun toggleCrosshair(enable: Boolean) {
        if (enable && !Settings.canDrawOverlays(this)) { requestOverlayPermission(); return }
        if (enable) startCrosshairService() else stopService(Intent(this, CrosshairOverlayService::class.java))
        isCrosshairRunning = enable
    }

    private fun startCrosshairService() {
        val intent = Intent(this, CrosshairOverlayService::class.java)
        intent.putExtra("scope_asset_name", selectedCrosshair)
        startForegroundService(intent)
    }

    private fun toggleVpn(enable: Boolean) {
        if (enable) {
            val vpnIntent = VpnService.prepare(this)
            if (vpnIntent != null) vpnPermissionLauncher.launch(vpnIntent)
            else startVpnServiceInternal()
        } else {
            val intent = Intent(this, GameVpnService::class.java).apply { action = GameVpnService.ACTION_DISCONNECT }
            startService(intent)
            isVpnRunning = false
        }
    }

    private fun toggleDnd(enable: Boolean) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (enable && !nm.isNotificationPolicyAccessGranted) {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return
        }
        nm.setInterruptionFilter(if (enable) android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY else android.app.NotificationManager.INTERRUPTION_FILTER_ALL)
        isDndEnabled = enable
    }

    private fun startVpnServiceInternal() {
        val intent = Intent(this, GameVpnService::class.java).apply { 
            action = GameVpnService.ACTION_CONNECT 
            gameList.firstOrNull()?.let { putExtra(GameVpnService.EXTRA_GAME_PACKAGE, it.packageName) }
        }
        startForegroundService(intent)
        isVpnRunning = true
    }

    private fun requestOverlayPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())
        overlayPermissionLauncher.launch(intent)
    }

    private fun initPermissionLaunchers() {
        vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result -> if (result.resultCode == RESULT_OK) startVpnServiceInternal() }
        overlayPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { syncState() }
    }

    private fun checkRootStatus() {
        lifecycleScope.launch(Dispatchers.IO) {
            val rooted = try { Shell.cmd("id").exec().isSuccess } catch (_: Exception) { false }
            withContext(Dispatchers.Main) { isRootedCached = rooted }
        }
    }

    private fun checkAudioPermission() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            androidx.core.app.ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO), 1002)
        }
    }

    private fun checkAndBindShizuku() {
        if (!Shizuku.pingBinder()) return
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bindShizukuService()
    }

    private fun bindShizukuService() {
        if (fileService != null) return
        try {
            val args = Shizuku.UserServiceArgs(ComponentName(this, FileService::class.java))
                .daemon(false).processNameSuffix("file_service").version(BuildConfig.VERSION_CODE)
            Shizuku.bindUserService(args, serviceConnection)
        } catch (_: Exception) {}
    }

    private fun scanForGames() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val userAdded = appPrefs.getStringSet("user_games", emptySet()) ?: emptySet()
            val removedGames = appPrefs.getStringSet("removed_games", emptySet()) ?: emptySet()
            
            // Query for launcher activities, including disabled ones if we want to keep them in sync
            val intent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_ALL
            val activities = try {
                pm.queryIntentActivities(intent, flags)
            } catch (_: Exception) {
                pm.queryIntentActivities(intent, 0)
            }

            val newGames = mutableListOf<GameInfo>()
            for (ri in activities) {
                val ai = ri.activityInfo.applicationInfo
                val pkg = ai.packageName
                if (pkg in removedGames) continue
                
                if (ai.category == ApplicationInfo.CATEGORY_GAME || pkg in userAdded) {
                    newGames.add(GameInfo(ai.loadLabel(pm).toString(), pkg, ai.loadIcon(pm), null))
                }
            }
            withContext(Dispatchers.Main) {
                gameList.clear()
                gameList.addAll(newGames.distinctBy { it.packageName })
            }
        }
    }

    private fun loadAllApps() {
        lifecycleScope.launch(Dispatchers.IO) {
            val pm = packageManager
            val flags = PackageManager.GET_META_DATA or PackageManager.MATCH_DISABLED_COMPONENTS
            val apps = pm.getInstalledApplications(flags)
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 || it.category == ApplicationInfo.CATEGORY_GAME }
                .map { ai -> GameInfo(ai.loadLabel(pm).toString(), ai.packageName, ai.loadIcon(pm), null) }
                .sortedBy { it.appName.lowercase() }
            
            withContext(Dispatchers.Main) {
                allAppsList.clear()
                allAppsList.addAll(apps)
            }
        }
    }

    private fun addGameToLibrary(pkg: String) {
        val userAdded = appPrefs.getStringSet("user_games", emptySet())?.toMutableSet() ?: mutableSetOf()
        val removedGames = appPrefs.getStringSet("removed_games", emptySet())?.toMutableSet() ?: mutableSetOf()
        
        userAdded.add(pkg)
        removedGames.remove(pkg)
        
        appPrefs.edit()
            .putStringSet("user_games", userAdded)
            .putStringSet("removed_games", removedGames)
            .apply()
        scanForGames()
    }

    private fun removeGameFromLibrary(pkg: String) {
        val userAdded = appPrefs.getStringSet("user_games", emptySet())?.toMutableSet() ?: mutableSetOf()
        val removedGames = appPrefs.getStringSet("removed_games", emptySet())?.toMutableSet() ?: mutableSetOf()
        
        userAdded.remove(pkg)
        removedGames.add(pkg)
        
        appPrefs.edit()
            .putStringSet("user_games", userAdded)
            .putStringSet("removed_games", removedGames)
            .apply()
        scanForGames()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::shizukuManager.isInitialized) {
            shizukuManager.destroy()
        }
        if (::boostController.isInitialized) {
            boostController.release()
        }
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        try { unregisterReceiver(serviceStateReceiver) } catch (_: Exception) {}
    }
}
