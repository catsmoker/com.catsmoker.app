package com.catsmoker.app;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.topjohnwu.superuser.Shell;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import android.widget.HorizontalScrollView;

import rikka.shizuku.Shizuku;

public class FeaturesActivity extends AppCompatActivity {

    public static final String DNS_PREFS = "DnsPrefs";
    public static final String KEY_CUSTOM_DNS = "custom_dns";
    public static final String KEY_DNS_METHOD = "dns_method";
    public static final String KEY_DNS_PROVIDER_INDEX = "dns_provider_index";
    private static final int SHIZUKU_PERMISSION_REQUEST_CODE = 1001;

    // Standardized DNS Options
    private static final String[] DNS_OPTIONS = {
            "Default (DHCP)",
            "Custom",
            "Google (8.8.8.8)",
            "Cloudflare (1.1.1.1)"
    };

    // UI Components
    private GameAdapter gameAdapter;
    private MaterialSwitch dndSwitch, playTimeSwitch, vpnSwitch, btnToggleCrosshair, btnToggleOverlay;
    private AutoCompleteTextView dnsSpinnerRoot, dnsSpinnerVpn;
    private Button btnApplyDns, btnCleanRoot, btnCleanShizuku, btnCleanDefault, btnViewAll;
    private TextView cleanSystemSummaryText, logTextView;
    private View rootLayout, logScrollView;
    private ChipGroup crosshairChipGroup;
    private MaterialButtonToggleGroup dnsMethodToggleGroup;
    private View rootDnsOptions, vpnDnsOptions;
    private TextInputLayout vpnCustomDnsLayout;
    private TextInputEditText dnsEditText;
    private HorizontalScrollView crosshairStylePicker;

    // State
    private final List<GameInfo> gameList = new ArrayList<>();
    private boolean isRootedCached = false;
    private int selectedScopeResourceId = R.drawable.scope2;
    private ExecutorService executorService;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Shizuku
    private IFileService fileService;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            fileService = IFileService.Stub.asInterface(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            fileService = null;
        }
    };

    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> fileService = null;
    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> checkAndBindShizuku();
    private final Shizuku.OnRequestPermissionResultListener requestPermissionResultListener = (requestCode, grantResult) -> {
        if (requestCode == SHIZUKU_PERMISSION_REQUEST_CODE) {
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                bindShizukuService();
            } else {
                showSnackbar("Shizuku permission denied");
            }
        }
    };

    // Permission Launchers
    private ActivityResultLauncher<Intent> vpnPermissionLauncher;
    private ActivityResultLauncher<Intent> overlayPermissionLauncher;
    private ActivityResultLauncher<Intent> usageStatsLauncher;
    private ActivityResultLauncher<Intent> notificationPolicyLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_features);

        executorService = Executors.newSingleThreadExecutor();
        Shell.getShell(); // Init Root Shell

        initPermissionLaunchers();
        initializeViews();
        loadSavedSettings();
        setupLogic();

        // Initial Data Load
        checkRootStatus();

        // Scan in onCreate, will also happen in onResume
        scanForGames();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
            }
        });

        Shizuku.addBinderReceivedListener(binderReceivedListener);
        Shizuku.addBinderDeadListener(binderDeadListener);
        Shizuku.addRequestPermissionResultListener(requestPermissionResultListener);
        
        checkAndBindShizuku();
    }

    private void checkAndBindShizuku() {
        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                bindShizukuService();
            } else {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE);
            }
        }
    }

    private void bindShizukuService() {
        if (fileService != null) return;
        try {
            Shizuku.UserServiceArgs args = new Shizuku.UserServiceArgs(new ComponentName(this, FileService.class))
                    .daemon(false)
                    .processNameSuffix("file_service")
                    .debuggable(BuildConfig.DEBUG)
                    .version(BuildConfig.VERSION_CODE);
            Shizuku.bindUserService(args, serviceConnection);
        } catch (Exception e) {
            showSnackbar("Shizuku bind failed: " + e.getMessage());
        }
    }

    private void initPermissionLaunchers() {
        vpnPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        startVpnServiceInternal();
                        vpnSwitch.setChecked(true);
                        saveSwitchState("vpn_enabled", true);
                    } else {
                        showSnackbar("VPN permission denied");
                        vpnSwitch.setChecked(false);
                    }
                });

        overlayPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (canDrawOverlays()) {
                        showSnackbar("Overlay permission granted");
                    } else {
                        showSnackbar("Overlay permission required");
                        btnToggleOverlay.setChecked(false);
                        btnToggleCrosshair.setChecked(false);
                    }
                });

        usageStatsLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    // Re-check logic in onResume
                });

        notificationPolicyLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    // Re-check logic in onResume
                });
    }

    private void initializeViews() {
        rootLayout = findViewById(android.R.id.content);

        // Switches
        dndSwitch = findViewById(R.id.dnd_switch);
        playTimeSwitch = findViewById(R.id.play_time_switch);
        vpnSwitch = findViewById(R.id.vpn_switch);
        btnToggleOverlay = findViewById(R.id.btn_toggle_overlay);
        btnToggleCrosshair = findViewById(R.id.btn_toggle_crosshair);

        // DNS Section
        dnsSpinnerRoot = findViewById(R.id.dns_spinner_root);
        dnsSpinnerVpn = findViewById(R.id.dns_spinner_vpn);
        btnApplyDns = findViewById(R.id.btn_apply_dns);
        dnsMethodToggleGroup = findViewById(R.id.dns_method_radio_group);

        rootDnsOptions = findViewById(R.id.root_dns_options);
        vpnDnsOptions = findViewById(R.id.vpn_dns_options);
        vpnCustomDnsLayout = findViewById(R.id.vpn_custom_dns_layout);
        dnsEditText = findViewById(R.id.dns_edit_text);

        // Games & Crosshair
        RecyclerView gamesRecyclerView = findViewById(R.id.games_recycler_view);
        crosshairChipGroup = findViewById(R.id.crosshair_chip_group);
        crosshairStylePicker = findViewById(R.id.crosshair_style_picker);
        btnViewAll = findViewById(R.id.btn_view_all); // Make sure this ID exists in XML

        // System Maintenance
        btnCleanRoot = findViewById(R.id.btn_clean_system);
        btnCleanShizuku = findViewById(R.id.btn_clean_shizuku);
        btnCleanDefault = findViewById(R.id.btn_clean_default);

        cleanSystemSummaryText = findViewById(R.id.clean_system_summary_text);
        logScrollView = findViewById(R.id.log_scroll_view);
        logTextView = findViewById(R.id.log_text_view);


        // Recycler Setup
        gameAdapter = new GameAdapter(this, gameList);
        gamesRecyclerView.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        gamesRecyclerView.setAdapter(gameAdapter);
    }

    private void loadSavedSettings() {
        SharedPreferences prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE);
        
        // Load Overlay Settings
        boolean overlayEnabled = prefs.getBoolean("overlay_enabled", false);
        btnToggleOverlay.setChecked(overlayEnabled);
        
        boolean crosshairEnabled = prefs.getBoolean("crosshair_enabled", false);
        btnToggleCrosshair.setChecked(crosshairEnabled);
        crosshairStylePicker.setVisibility(crosshairEnabled ? View.VISIBLE : View.GONE);
        
        selectedScopeResourceId = prefs.getInt("selected_scope", R.drawable.scope2);
        
        // Load DND and Play Time
        dndSwitch.setChecked(prefs.getBoolean("dnd_enabled", false));
        playTimeSwitch.setChecked(prefs.getBoolean("play_time_enabled", false));
        
        // Load VPN state (caution: service might not be running)
        vpnSwitch.setChecked(prefs.getBoolean("vpn_enabled", false));
    }

    private void saveSwitchState(String key, boolean value) {
        getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().putBoolean(key, value).apply();
    }

    private void setupLogic() {
        setupDndSwitch();
        setupPlayTimeSwitch();
        setupVpnSwitch();
        setupOverlayFeature();
        setupCrosshairFeature();
        setupDnsFeature();
        setupScopeSelection();
        setupCleanButtons();
        setupViewAllButton();
    }

    private void setupViewAllButton() {
        if (btnViewAll != null) {
            btnViewAll.setOnClickListener(v -> showAppSelectionDialog());
        }
    }

    private void showAppSelectionDialog() {
        PackageManager pm = getPackageManager();
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities = pm.queryIntentActivities(mainIntent, 0);
        
        // Sort by name
        activities.sort((a, b) -> a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString()));

        String[] appNames = new String[activities.size()];
        boolean[] checkedItems = new boolean[activities.size()];
        Set<String> manuallyAdded = getSharedPreferences("AppPrefs", MODE_PRIVATE).getStringSet("manual_games", new HashSet<>());

        for (int i = 0; i < activities.size(); i++) {
            appNames[i] = activities.get(i).loadLabel(pm).toString();
            checkedItems[i] = manuallyAdded.contains(activities.get(i).activityInfo.packageName);
        }

        new AlertDialog.Builder(this)
                .setTitle("Select Games")
                .setMultiChoiceItems(appNames, checkedItems, (dialog, which, isChecked) -> {
                    String pkg = activities.get(which).activityInfo.packageName;
                    Set<String> current = new HashSet<>(getSharedPreferences("AppPrefs", MODE_PRIVATE).getStringSet("manual_games", new HashSet<>()));
                    if (isChecked) current.add(pkg);
                    else current.remove(pkg);
                    getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().putStringSet("manual_games", current).apply();
                })
                .setPositiveButton("Done", (dialog, which) -> scanForGames())
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncUIWithSystemState();
        // Trigger scan to update time and battery status
        scanForGames();
    }

    private void syncUIWithSystemState() {
        if (dndSwitch != null) {
            dndSwitch.setOnCheckedChangeListener(null);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                boolean dndActive = nm.getCurrentInterruptionFilter() != NotificationManager.INTERRUPTION_FILTER_ALL;
                dndSwitch.setChecked(dndActive);
                saveSwitchState("dnd_enabled", dndActive);
            }
            setupDndSwitch();
        }

        if (btnToggleOverlay != null) {
            btnToggleOverlay.setOnCheckedChangeListener(null);
            boolean isRunning = PerformanceOverlayService.isRunning && canDrawOverlays();
            btnToggleOverlay.setChecked(isRunning);
            saveSwitchState("overlay_enabled", isRunning);
            setupOverlayFeature();
        }

        if (btnToggleCrosshair != null) {
            btnToggleCrosshair.setOnCheckedChangeListener(null);
            boolean isRunning = CrosshairOverlayService.isRunning && canDrawOverlays();
            btnToggleCrosshair.setChecked(isRunning);
            saveSwitchState("crosshair_enabled", isRunning);
            setupCrosshairFeature();
        }

        if (playTimeSwitch != null) {
            playTimeSwitch.setOnCheckedChangeListener(null);
            boolean hasPerm = hasUsageStatsPermission();
            playTimeSwitch.setChecked(playTimeSwitch.isChecked() && hasPerm);
            setupPlayTimeSwitch();
        }

        if (vpnSwitch != null) {
            vpnSwitch.setOnCheckedChangeListener(null);
            boolean isRunning = isVpnServiceRunning();
            vpnSwitch.setChecked(isRunning);
            saveSwitchState("vpn_enabled", isRunning);
            setupVpnSwitch();
        }
    }

    // --- Overlay & Crosshair ---

    private void setupOverlayFeature() {
        btnToggleOverlay.setOnCheckedChangeListener((buttonView, isChecked) -> {
            saveSwitchState("overlay_enabled", isChecked);
            if (isChecked) {
                if (!canDrawOverlays()) {
                    buttonView.setChecked(false);
                    requestOverlayPermission();
                } else {
                    togglePerformanceOverlayService(true);
                }
            } else {
                togglePerformanceOverlayService(false);
            }
        });
    }

    private void setupCrosshairFeature() {
        btnToggleCrosshair.setOnCheckedChangeListener((buttonView, isChecked) -> {
            saveSwitchState("crosshair_enabled", isChecked);
            crosshairStylePicker.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            if (isChecked) {
                if (!canDrawOverlays()) {
                    buttonView.setChecked(false);
                    requestOverlayPermission();
                } else {
                    startCrosshairService();
                }
            } else {
                stopService(new Intent(this, CrosshairOverlayService.class));
            }
        });
    }

    private void togglePerformanceOverlayService(boolean enable) {
        Intent intent = new Intent(this, PerformanceOverlayService.class);
        if (enable) {
            startForegroundService(intent);
        } else {
            stopService(intent);
        }
    }

    private void startCrosshairService() {
        Intent serviceIntent = new Intent(this, CrosshairOverlayService.class);
        serviceIntent.putExtra(CrosshairOverlayService.EXTRA_SCOPE_RESOURCE_ID, selectedScopeResourceId);
        startForegroundService(serviceIntent);
    }

    // --- VPN ---

    private void setupVpnSwitch() {
        vpnSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            saveSwitchState("vpn_enabled", isChecked);
            if (isChecked) {
                Intent vpnIntent = VpnService.prepare(this);
                if (vpnIntent != null) {
                    vpnPermissionLauncher.launch(vpnIntent);
                } else {
                    startVpnServiceInternal();
                }
            } else {
                Intent intent = new Intent(this, GameVpnService.class);
                intent.setAction(GameVpnService.ACTION_DISCONNECT);
                startService(intent);
            }
        });
    }

    private void startVpnServiceInternal() {
        Intent intent = new Intent(this, GameVpnService.class);
        intent.setAction(GameVpnService.ACTION_CONNECT);
        startForegroundService(intent);
    }

    @SuppressWarnings("deprecation")
    private boolean isVpnServiceRunning() {
        ActivityManager manager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (manager != null) {
            for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
                if (GameVpnService.class.getName().equals(service.service.getClassName())) {
                    return true;
                }
            }
        }
        return false;
    }

    // --- DNS Logic ---

    private void setupDnsFeature() {
        SharedPreferences dnsPrefs = getSharedPreferences(DNS_PREFS, MODE_PRIVATE);

        String savedCustomDns = dnsPrefs.getString(KEY_CUSTOM_DNS, "");
        if (dnsEditText != null) dnsEditText.setText(savedCustomDns);

        int savedDnsMethodId = dnsPrefs.getInt(KEY_DNS_METHOD, R.id.radio_dns_root);
        if (savedDnsMethodId != R.id.radio_dns_root && savedDnsMethodId != R.id.radio_dns_vpn) {
            savedDnsMethodId = R.id.radio_dns_root;
        }

        setupDnsSpinners(dnsPrefs);

        dnsMethodToggleGroup.check(savedDnsMethodId);
        updateDnsUI();

        dnsMethodToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) updateDnsUI();
        });

        btnApplyDns.setOnClickListener(v -> applyDnsChanges());
    }

    private void setupDnsSpinners(SharedPreferences prefs) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, DNS_OPTIONS);

        dnsSpinnerRoot.setAdapter(adapter);
        dnsSpinnerVpn.setAdapter(adapter);

        int savedIndex = prefs.getInt(KEY_DNS_PROVIDER_INDEX, 0);
        if (savedIndex < DNS_OPTIONS.length) {
            String selectedText = DNS_OPTIONS[savedIndex];
            // false flag prevents the dropdown from showing
            dnsSpinnerRoot.setText(selectedText, false);
            dnsSpinnerVpn.setText(selectedText, false);
        }

        // Use OnItemClickListener for AutoCompleteTextView
        dnsSpinnerRoot.setOnItemClickListener((parent, view, position, id) -> updateDnsUI());
        dnsSpinnerVpn.setOnItemClickListener((parent, view, position, id) -> updateDnsUI());
    }

    private void updateDnsUI() {
        int checkedId = dnsMethodToggleGroup.getCheckedButtonId();
        boolean isRootMode = (checkedId == R.id.radio_dns_root);

        AutoCompleteTextView activeSpinner = isRootMode ? dnsSpinnerRoot : dnsSpinnerVpn;
        String selectedOption = activeSpinner.getText().toString();
        boolean isCustomSelected = selectedOption.equals("Custom");

        rootDnsOptions.setVisibility(isRootMode ? View.VISIBLE : View.GONE);
        vpnDnsOptions.setVisibility(isRootMode ? View.GONE : View.VISIBLE);
        vpnCustomDnsLayout.setVisibility(isCustomSelected ? View.VISIBLE : View.GONE);
    }

    private int getDnsOptionIndex(String option) {
        return Arrays.asList(DNS_OPTIONS).indexOf(option);
    }

    @SuppressLint("SetTextI18n")
    private void applyDnsChanges() {
        SharedPreferences dnsPrefs = getSharedPreferences(DNS_PREFS, MODE_PRIVATE);
        int methodId = dnsMethodToggleGroup.getCheckedButtonId();

        SharedPreferences.Editor editor = dnsPrefs.edit();
        editor.putInt(KEY_DNS_METHOD, methodId);

        int selectedIndex;
        if (methodId == R.id.radio_dns_root) {
            selectedIndex = getDnsOptionIndex(dnsSpinnerRoot.getText().toString());
        } else {
            selectedIndex = getDnsOptionIndex(dnsSpinnerVpn.getText().toString());
        }
        editor.putInt(KEY_DNS_PROVIDER_INDEX, selectedIndex);

        if (methodId == R.id.radio_dns_root) {
            applyRootDns();
            editor.apply();
        } else {
            applyVpnDns(editor);
            if (isVpnServiceRunning()) {
                showSnackbar("Restart VPN to apply changes");
            } else {
                showSnackbar("DNS Saved. Start VPN to use.");
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private void applyRootDns() {
        if (!isRootedCached) {
            showSnackbar("Root access not detected!");
            return;
        }

        String selected = dnsSpinnerRoot.getText().toString();

        String cmd;
        if (selected.contains("Google")) {
            cmd = "setprop net.dns1 8.8.8.8; setprop net.dns2 8.8.4.4";
        } else if (selected.contains("Cloudflare")) {
            cmd = "setprop net.dns1 1.1.1.1; setprop net.dns2 1.0.0.1";
        } else if (selected.equals("Custom")) {
            String customIp = Objects.requireNonNull(dnsEditText.getText()).toString().trim();
            if (customIp.isEmpty()) {
                showSnackbar("Please enter a valid IP");
                return;
            }
            String[] ips = customIp.split(",");
            String dns1 = ips[0].trim();
            String dns2 = (ips.length > 1) ? ips[1].trim() : "";
            cmd = "setprop net.dns1 " + dns1 + "; setprop net.dns2 " + dns2;
        } else {
            cmd = "setprop net.dns1 \"\"; setprop net.dns2 \"\"";
        }

        executorService.execute(() -> {
            boolean success = Shell.cmd(cmd).exec().isSuccess();
            mainHandler.post(() -> showSnackbar(success ? "DNS Applied via Root" : "Root Command Failed"));
        });
    }

    private void applyVpnDns(SharedPreferences.Editor editor) {
        String selected = dnsSpinnerVpn.getText().toString();
        String dnsToSave;

        if (selected.equals("Custom")) {
            dnsToSave = Objects.requireNonNull(dnsEditText.getText()).toString();
        } else if (selected.contains("Google")) {
            dnsToSave = "8.8.8.8,8.8.4.4";
        } else if (selected.contains("Cloudflare")) {
            dnsToSave = "1.1.1.1,1.0.0.1";
        } else {
            dnsToSave = "";
        }

        editor.putString(KEY_CUSTOM_DNS, dnsToSave);
        editor.apply();
    }

    // --- Permissions & Helpers ---

    private void setupDndSwitch() {
        dndSwitch.setOnCheckedChangeListener((view, isChecked) -> {
            saveSwitchState("dnd_enabled", isChecked);
            if (isChecked) {
                if (isNotificationPolicyAccessDenied()) {
                    view.setChecked(false);
                    Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
                    notificationPolicyLauncher.launch(intent);
                } else {
                    setDndMode(true);
                }
            } else {
                setDndMode(false);
            }
        });
    }

    private void setupPlayTimeSwitch() {
        playTimeSwitch.setOnCheckedChangeListener((view, isChecked) -> {
            saveSwitchState("play_time_enabled", isChecked);
            if (isChecked) {
                if (!hasUsageStatsPermission()) {
                    view.setChecked(false);
                    Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
                    usageStatsLauncher.launch(intent);
                } else {
                    // Just refresh list if already has permission
                    scanForGames();
                }
            } else {
                // If turned off, refresh list to hide times
                scanForGames();
            }
        });
    }

    @SuppressLint("SetTextI18n")
    private void setDndMode(boolean enable) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null || isNotificationPolicyAccessDenied()) return;

        if (enable) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY);
            showSnackbar("DND Enabled");
        } else {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL);
            showSnackbar("DND Disabled");
        }
    }

    private boolean isNotificationPolicyAccessDenied() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        return nm != null && !nm.isNotificationPolicyAccessGranted();
    }

    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private boolean canDrawOverlays() {
        return Settings.canDrawOverlays(this);
    }

    private void requestOverlayPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
        overlayPermissionLauncher.launch(intent);
    }

    // --- Data Loading & Usage Stats ---

    private void checkRootStatus() {
        executorService.execute(() -> {
            boolean rooted = Shell.cmd("id").exec().isSuccess();
            mainHandler.post(() -> {
                isRootedCached = rooted;
                if (dnsMethodToggleGroup != null) {
                    View rootBtn = dnsMethodToggleGroup.findViewById(R.id.radio_dns_root);
                    rootBtn.setEnabled(rooted);
                    if (!rooted && dnsMethodToggleGroup.getCheckedButtonId() == R.id.radio_dns_root) {
                        dnsMethodToggleGroup.check(R.id.radio_dns_vpn);
                    }
                }
                // Dynamically update the System Clean UI
                updateCleanUI(rooted);
            });
        });
    }

    private void updateCleanUI(boolean rooted) {
        if (btnCleanRoot != null) {
            btnCleanRoot.setEnabled(rooted);
        }
        if (cleanSystemSummaryText != null) {
            if (rooted || Shizuku.pingBinder()) {
                cleanSystemSummaryText.setText(getString(R.string.clean_system_summary));
            } else {
                cleanSystemSummaryText.setText(getString(R.string.clean_system_summary_no_root));
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private void scanForGames() {
        executorService.execute(() -> {
            PackageManager pm = getPackageManager();
            Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
            mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> activities = pm.queryIntentActivities(mainIntent, 0);
            List<GameInfo> newGames = new ArrayList<>();

            Set<String> manuallyAdded = getSharedPreferences("AppPrefs", MODE_PRIVATE).getStringSet("manual_games", new HashSet<>());

            // Check if tracking enabled and permission granted
            boolean trackingEnabled = (playTimeSwitch != null && playTimeSwitch.isChecked()) && hasUsageStatsPermission();
            Map<String, UsageStats> statsMap = trackingEnabled ? getAppUsageStats() : null;

            for (ResolveInfo ri : activities) {
                ApplicationInfo ai = ri.activityInfo.applicationInfo;
                if (isGame(ai) || manuallyAdded.contains(ai.packageName)) {
                    String formattedTime = null;
                    if (trackingEnabled && statsMap != null) {
                        UsageStats stats = statsMap.get(ai.packageName);
                        if (stats != null) {
                            formattedTime = formatDuration(stats.getTotalTimeInForeground());
                        } else {
                            formattedTime = "0m";
                        }
                    }

                    newGames.add(new GameInfo(
                            ai.loadLabel(pm).toString(),
                            ai.packageName,
                            ai.loadIcon(pm), // Use loadIcon for efficiency
                            formattedTime // Pass the formatted time (can be null)
                    ));
                }
            }
            mainHandler.post(() -> {
                gameList.clear();
                gameList.addAll(newGames);
                gameAdapter.notifyDataSetChanged();
            });
        });
    }

    private Map<String, UsageStats> getAppUsageStats() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        Calendar calendar = Calendar.getInstance();
        long endTime = calendar.getTimeInMillis();
        calendar.add(Calendar.YEAR, -1); // Get stats for the last year
        long startTime = calendar.getTimeInMillis();

        // Query Usage Stats
        return usm.queryAndAggregateUsageStats(startTime, endTime);
    }

    @SuppressLint("DefaultLocale")
    private String formatDuration(long millis) {
        if (millis < 60000) return "Start"; // Less than a minute
        long hours = TimeUnit.MILLISECONDS.toHours(millis);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60;
        if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else {
            return String.format("%dm", minutes);
        }
    }

    private boolean isGame(ApplicationInfo appInfo) {
        if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) return false;
        return appInfo.category == ApplicationInfo.CATEGORY_GAME;
    }

    // --- Crosshair Scope Selection ---

    @SuppressLint("SetTextI18n")
    private void setupScopeSelection() {
        int[] drawables = {
                R.drawable.scope1, R.drawable.scope2, R.drawable.scope3,
                R.drawable.scope4, R.drawable.scope5, R.drawable.scope6, R.drawable.scope7
        };

        crosshairChipGroup.removeAllViews();
        for (int i = 0; i < drawables.length; i++) {
            int resId = drawables[i];
            Chip chip = new Chip(this);
            chip.setId(View.generateViewId());
            chip.setTag(resId);
            chip.setText("Scope " + (i + 1));
            chip.setCheckable(true);
            chip.setChipIconResource(resId);

            if (resId == selectedScopeResourceId) chip.setChecked(true);

            crosshairChipGroup.addView(chip);
        }

        crosshairChipGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (!checkedIds.isEmpty()) {
                Chip chip = group.findViewById(checkedIds.get(0));
                if (chip != null) {
                    int resId = (Integer) chip.getTag();
                    getSharedPreferences("AppPrefs", MODE_PRIVATE).edit().putInt("selected_scope", resId).apply();
                    selectScope(resId);
                }
            }
        });
    }

    private void selectScope(int resId) {
        selectedScopeResourceId = resId;
        if (CrosshairOverlayService.isRunning) {
            startCrosshairService();
        }
    }

    private void setupCleanButtons() {
        btnCleanRoot.setOnClickListener(v -> executeRootClean());
        btnCleanShizuku.setOnClickListener(v -> executeShizukuClean());
        btnCleanDefault.setOnClickListener(v -> executeNonRootClean());
    }

    @SuppressLint("SetTextI18n")
    private void executeRootClean() {
        logTextView.setText("> Starting Root Clean...\n");
        executorService.execute(() -> {
            String[] steps = {
                "Cleaning app caches...",
                "Removing empty files...",
                "Removing empty folders...",
                "Deleting hidden data..."
            };
            
            String[] commands = {
                "find /data/user/0/*/cache -delete; find /data/user/0/*/code_cache -delete",
                "find /storage/emulated/0 -type f -size 0 -delete",
                "find /storage/emulated/0 -type d -empty -delete",
                "find /storage/emulated/0 -name '.*' -delete"
            };

            for (int i = 0; i < commands.length; i++) {
                String step = steps[i];
                String cmd = commands[i];
                mainHandler.post(() -> logTextView.append("> " + step + "\n"));
                Shell.cmd(cmd).exec();
            }

            mainHandler.post(() -> {
                logTextView.append("> Root Cleaning Complete.\n");
                showSnackbar("Root cleaning successful.");
            });
        });
    }

    @SuppressLint("SetTextI18n")
    private void executeShizukuClean() {
        if (fileService == null) {
            if (Shizuku.pingBinder()) {
                checkAndBindShizuku();
                showSnackbar("Connecting to Shizuku...");
            } else {
                showSnackbar("Shizuku not running!");
            }
            return;
        }

        logTextView.setText("> Starting Shizuku Clean...\n");
        executorService.execute(() -> {
            String[] steps = {
                "Cleaning app caches...",
                "Removing empty files...",
                "Removing empty folders...",
                "Deleting hidden data..."
            };
            
            String[] commands = {
                "find /data/user/0/*/cache -delete; find /data/user/0/*/code_cache -delete",
                "find /storage/emulated/0 -type f -size 0 -delete",
                "find /storage/emulated/0 -type d -empty -delete",
                "find /storage/emulated/0 -name '.*' -delete"
            };

            try {
                for (int i = 0; i < commands.length; i++) {
                    String step = steps[i];
                    String cmd = commands[i];
                    mainHandler.post(() -> logTextView.append("> " + step + "\n"));
                    fileService.executeCommand(new String[]{"sh", "-c", cmd});
                }
                mainHandler.post(() -> {
                    logTextView.append("> Shizuku Cleaning Complete.\n");
                    showSnackbar("Shizuku cleaning successful.");
                });
            } catch (Exception e) {
                mainHandler.post(() -> logTextView.append("> Error: " + e.getMessage() + "\n"));
            }
        });
    }

    @SuppressLint("SetTextI18n")
    private void executeNonRootClean() {
        logTextView.setText("> Starting Default Clean...\n");
        logTextView.append("> Clearing internal app cache...\n");
        try {
            File cacheDir = getCacheDir();
            if (cacheDir != null && cacheDir.isDirectory()) {
                deleteDir(cacheDir);
            }
            logTextView.append("> Internal cache cleared.\n");
            logTextView.append("> Opening system storage settings...\n");
            
            mainHandler.postDelayed(() -> {
                showSnackbar(getString(R.string.storage_settings_opened));
                Intent intent = new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
                if (intent.resolveActivity(getPackageManager()) != null) {
                    startActivity(intent);
                } else {
                    startActivity(new Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS));
                }
                logTextView.append("> Default process finished.\n");
            }, 1000);
        } catch (Exception e) {
            logTextView.append("> Error: " + e.getMessage() + "\n");
        }
    }

    public static boolean deleteDir(File dir) {
        if (dir != null && dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null) {
                for (String child : children) {
                    boolean success = deleteDir(new File(dir, child));
                    if (!success) {
                        return false;
                    }
                }
            }
            return dir.delete();
        } else if (dir != null && dir.isFile()) {
            return dir.delete();
        }
        return false;
    }

    // --- UI Helpers ---

    private void showSnackbar(String msg) {
        if (rootLayout != null) {
            Snackbar.make(rootLayout, msg, Snackbar.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        }
    }

    public void requestIgnoreBatteryOptimizationsWrapper() {
        try {
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            startActivity(intent);
        } catch (Exception e) {
            showSnackbar("Cannot open Battery Settings");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdown();
        }
        Shizuku.removeBinderReceivedListener(binderReceivedListener);
        Shizuku.removeBinderDeadListener(binderDeadListener);
        Shizuku.removeRequestPermissionResultListener(requestPermissionResultListener);
    }

    // --- Inner Classes ---

    // Updated Record to include playTime
    public record GameInfo(String appName, String packageName, Drawable icon, String playTime) {}

    public static class GameAdapter extends RecyclerView.Adapter<GameAdapter.GameViewHolder> {
        private final WeakReference<FeaturesActivity> activityRef;
        private final List<GameInfo> gameList;

        public GameAdapter(FeaturesActivity activity, List<GameInfo> list) {
            this.activityRef = new WeakReference<>(activity);
            this.gameList = list;
        }

        @NonNull @Override
        public GameViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_item_game, parent, false);
            return new GameViewHolder(v);
        }

        @SuppressLint("SetTextI18n")
        @Override
        public void onBindViewHolder(@NonNull GameViewHolder holder, int position) {
            Context ctx = holder.itemView.getContext(); // Move declaration here

            GameInfo info = gameList.get(position);
            holder.gameName.setText(info.appName());
            holder.gameIcon.setImageDrawable(info.icon());
            holder.gameIcon.setContentDescription(ctx.getString(R.string.game_icon_description, info.appName()));

            // --- BATTERY & TIME LOGIC ---
            PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);

            boolean isUnrestricted = false;
            if (pm != null) {
                isUnrestricted = pm.isIgnoringBatteryOptimizations(info.packageName());
            }

            // Status Text
            String statusText = isUnrestricted ? "Unrestricted" : "Optimized";

            // Append Time if available
            if (info.playTime() != null) {
                holder.gamePlayTime.setText(info.playTime() + " • " + statusText);
            } else {
                holder.gamePlayTime.setText(statusText);
            }

            // Colors based on battery status (independent of time presence)
            if (isUnrestricted) {
                holder.batteryOptimizationButton.setColorFilter(0xFF4CAF50); // Green
                holder.gamePlayTime.setTextColor(0xFF4CAF50);
            } else {
                holder.batteryOptimizationButton.setColorFilter(0xFFFFC107); // Orange
                holder.gamePlayTime.setTextColor(0xFFFFC107);
            }

            holder.batteryOptimizationButton.setOnClickListener(v -> {
                FeaturesActivity activity = activityRef.get();
                if (activity != null) activity.requestIgnoreBatteryOptimizationsWrapper();
            });

            holder.launchButton.setOnClickListener(v -> {
                Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(info.packageName());
                if (launch != null) {
                    ctx.startActivity(launch);
                } else {
                    Toast.makeText(ctx, "Cannot launch game", Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override public int getItemCount() { return gameList.size(); }

        public static class GameViewHolder extends RecyclerView.ViewHolder {
            ImageView gameIcon;
            TextView gameName, gamePlayTime;
            ImageButton batteryOptimizationButton;
            Button launchButton;

            public GameViewHolder(View v) {
                super(v);
                gameIcon = v.findViewById(R.id.game_icon);
                gameName = v.findViewById(R.id.game_name);
                gamePlayTime = v.findViewById(R.id.game_play_time);
                batteryOptimizationButton = v.findViewById(R.id.battery_optimization_button);
                launchButton = v.findViewById(R.id.launch_game_button);
            }
        }
    }
}