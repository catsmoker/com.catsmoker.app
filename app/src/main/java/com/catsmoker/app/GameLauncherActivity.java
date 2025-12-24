package com.catsmoker.app;

import android.app.AppOpsManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.VpnService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class GameLauncherActivity extends AppCompatActivity {

    private static final String TAG = "GameLauncherActivity";
    public static final String DNS_PREFS = "DnsPrefs";
    public static final String KEY_CUSTOM_DNS = "custom_dns";

    private RecyclerView gamesRecyclerView;
    private GameAdapter gameAdapter;
    private final List<GameInfo> gameList = new ArrayList<>();
    private MaterialSwitch dndSwitch;
    private MaterialSwitch playTimeSwitch;
    private MaterialSwitch vpnSwitch;

    private boolean dndEnabledByApp = false;
    private int previousDndMode = -1;

    private TextView networkStatusText;
    private LinearLayout networkSuggestions;
    private Button dataSaverButton;
    private Button netguardButton;
    private TextInputEditText dnsEditText;
    private Button saveDnsButton;

    private ActivityResultLauncher<Intent> vpnPermissionLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_launcher);

        vpnPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() != RESULT_OK) {
                        Toast.makeText(this, "Failed to get VPN permission.", Toast.LENGTH_SHORT).show();
                        vpnSwitch.setChecked(false);
                    }
                });

        initializeViews();
        setupLogic();
    }

    private void initializeViews() {
        dndSwitch = findViewById(R.id.dnd_switch);
        playTimeSwitch = findViewById(R.id.play_time_switch);
        vpnSwitch = findViewById(R.id.vpn_switch);
        gamesRecyclerView = findViewById(R.id.games_recycler_view);
        gamesRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        networkStatusText = findViewById(R.id.network_status_text);
        networkSuggestions = findViewById(R.id.network_suggestions);
        dataSaverButton = findViewById(R.id.data_saver_button);
        netguardButton = findViewById(R.id.netguard_button);
        dnsEditText = findViewById(R.id.dns_edit_text);
        saveDnsButton = findViewById(R.id.save_dns_button);
    }

    private void setupLogic() {
        gameAdapter = new GameAdapter(this, gameList);
        gamesRecyclerView.setAdapter(gameAdapter);

        setupDndSwitch();
        setupPlayTimeSwitch();
        setupNetworkStability();
        setupVpnSwitch();
        setupDnsSection();
        scanForGames();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (dndEnabledByApp) {
            setDndMode(false);
            dndEnabledByApp = false;
        }

        verifyPermissionsState();
        setupNetworkStability();

        if (gameAdapter != null) {
            gameAdapter.notifyItemRangeChanged(0, gameList.size());
        }
    }

    private void verifyPermissionsState() {
        if (dndSwitch.isChecked() && isNotificationPolicyAccessDenied()) {
            dndSwitch.setChecked(false);
        }
        if (playTimeSwitch.isChecked() && isUsageStatsPermissionMissing()) {
            playTimeSwitch.setChecked(false);
        }
    }

    private void setupDnsSection() {
        SharedPreferences dnsPrefs = getSharedPreferences(DNS_PREFS, MODE_PRIVATE);
        String customDns = dnsPrefs.getString(KEY_CUSTOM_DNS, "");
        if (dnsEditText != null) {
            dnsEditText.setText(customDns);
        }

        saveDnsButton.setOnClickListener(v -> {
            if (dnsEditText.getText() != null) {
                String dns = dnsEditText.getText().toString();
                dnsPrefs.edit().putString(KEY_CUSTOM_DNS, dns).apply();
                Toast.makeText(this, "DNS settings saved.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupVpnSwitch() {
        vpnSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                Intent vpnIntent = VpnService.prepare(this);
                if (vpnIntent != null) {
                    vpnPermissionLauncher.launch(vpnIntent);
                }
            } else {
                Intent intent = new Intent(this, GameVpnService.class);
                intent.setAction(GameVpnService.ACTION_DISCONNECT);
                startService(intent);
            }
        });
    }

    private void setupNetworkStability() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            updateNetworkUI("Network: Cannot access network service", false);
            return;
        }
        Network activeNetwork = cm.getActiveNetwork();
        if (activeNetwork == null) {
            updateNetworkUI("Network: Not connected", false);
            return;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
        if (caps == null) {
            updateNetworkUI("Network: Not connected", false);
            return;
        }

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            updateNetworkUI("Network: Wi-Fi", false);
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            updateNetworkUI("Network: Mobile Data", true);
        } else {
            updateNetworkUI("Network: Unknown", false);
        }

        dataSaverButton.setOnClickListener(v -> {
            // Using String literal to avoid 'Cannot resolve symbol' if compile SDK is older
            Intent intent = new Intent("android.settings.DATA_SAVER_SETTINGS");
            try {
                startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Error opening Data Saver settings", e);
                Toast.makeText(this, "Could not open Data Saver settings.", Toast.LENGTH_SHORT).show();
            }
        });

        netguardButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://netguard.me"));
            try {
                startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Error opening NetGuard link", e);
                Toast.makeText(this, "Could not open NetGuard website.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void updateNetworkUI(String status, boolean showSuggestions) {
        networkStatusText.setText(status);
        networkSuggestions.setVisibility(showSuggestions ? View.VISIBLE : View.GONE);
    }

    private void setupDndSwitch() {
        dndSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && isNotificationPolicyAccessDenied()) {
                requestNotificationPolicyAccess();
                buttonView.setChecked(false);
            }
        });
    }

    private void setupPlayTimeSwitch() {
        playTimeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && isUsageStatsPermissionMissing()) {
                requestUsageStatsPermission();
                buttonView.setChecked(false);
            }
        });
    }

    // Renamed to avoid "always inverted" warning
    private boolean isNotificationPolicyAccessDenied() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return !nm.isNotificationPolicyAccessGranted();
        }
        return false;
    }

    private void requestNotificationPolicyAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
            startActivity(intent);
            Toast.makeText(this, "Please grant Do Not Disturb access", Toast.LENGTH_LONG).show();
        }
    }

    // Renamed to avoid "always inverted" warning
    private boolean isUsageStatsPermissionMissing() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
        return mode != AppOpsManager.MODE_ALLOWED;
    }

    private void requestUsageStatsPermission() {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        startActivity(intent);
        Toast.makeText(this, "Please grant Usage Access permission", Toast.LENGTH_LONG).show();
    }

    private void setDndMode(boolean enable) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (isNotificationPolicyAccessDenied()) {
            return;
        }

        if (enable) {
            previousDndMode = notificationManager.getCurrentInterruptionFilter();
            notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALARMS);
            dndEnabledByApp = true;
        } else {
            if (previousDndMode != -1) {
                //noinspection WrongConstant
                notificationManager.setInterruptionFilter(previousDndMode);
                previousDndMode = -1;
            }
        }
    }

    private void scanForGames() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            List<GameInfo> newGameList = new ArrayList<>();

            for (ApplicationInfo packageInfo : packages) {
                if (isGame(packageInfo)) {
                    String appName = packageInfo.loadLabel(pm).toString();
                    String packageName = packageInfo.packageName;
                    Drawable icon = packageInfo.loadIcon(pm);
                    newGameList.add(new GameInfo(appName, packageName, icon));
                }
            }

            DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new GameDiffCallback(gameList, newGameList));

            new Handler(Looper.getMainLooper()).post(() -> {
                gameList.clear();
                gameList.addAll(newGameList);
                diffResult.dispatchUpdatesTo(gameAdapter);
            });
        }).start();
    }

    @SuppressWarnings("deprecation")
    private boolean isGame(ApplicationInfo appInfo) {
        if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (appInfo.category == ApplicationInfo.CATEGORY_GAME) {
                return true;
            }
        }
        return (appInfo.flags & ApplicationInfo.FLAG_IS_GAME) == ApplicationInfo.FLAG_IS_GAME;
    }

    public boolean isIgnoringBatteryOptimizationsWrapper(String packageName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm != null && pm.isIgnoringBatteryOptimizations(packageName);
        }
        return true;
    }

    // Removed unused 'packageName' parameter
    public void requestIgnoreBatteryOptimizationsWrapper() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Toast.makeText(this, "Find the game and disable battery optimization.", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Log.e(TAG, "Error opening battery optimization settings", e);
                Toast.makeText(this, "Could not open settings.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // --- Nested Classes ---

    private static class GameDiffCallback extends DiffUtil.Callback {
        private final List<GameInfo> oldList;
        private final List<GameInfo> newList;

        public GameDiffCallback(List<GameInfo> oldList, List<GameInfo> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override
        public int getOldListSize() { return oldList.size(); }
        @Override
        public int getNewListSize() { return newList.size(); }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            return oldList.get(oldItemPosition).packageName.equals(newList.get(newItemPosition).packageName);
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            return oldList.get(oldItemPosition).equals(newList.get(newItemPosition));
        }
    }

    @SuppressWarnings("ClassCanBeRecord")
    public static class GameInfo {
        private final String appName;
        private final String packageName;
        private final Drawable icon;

        public GameInfo(String appName, String packageName, Drawable icon) {
            this.appName = appName;
            this.packageName = packageName;
            this.icon = icon;
        }

        public String getAppName() { return appName; }
        public String getPackageName() { return packageName; }
        public Drawable getIcon() { return icon; }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            GameInfo gameInfo = (GameInfo) o;
            return Objects.equals(appName, gameInfo.appName) &&
                    Objects.equals(packageName, gameInfo.packageName);
        }

        @Override
        public int hashCode() {
            return Objects.hash(appName, packageName);
        }
    }

    public static class GameAdapter extends RecyclerView.Adapter<GameAdapter.GameViewHolder> {

        private final Context context;
        private final List<GameInfo> gameList;
        private final SharedPreferences playTimePrefs;

        public GameAdapter(Context context, List<GameInfo> gameList) {
            this.context = context;
            this.gameList = gameList;
            this.playTimePrefs = context.getSharedPreferences("PlayTime", Context.MODE_PRIVATE);
        }

        @NonNull
        @Override
        public GameViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(context).inflate(R.layout.list_item_game, parent, false);
            return new GameViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull GameViewHolder holder, int position) {
            GameInfo gameInfo = gameList.get(position);
            holder.gameName.setText(gameInfo.getAppName());
            holder.gameIcon.setImageDrawable(gameInfo.getIcon());

            long totalPlayTimeSeconds = playTimePrefs.getLong(gameInfo.getPackageName(), 0);
            holder.gamePlayTime.setText(formatPlayTime(totalPlayTimeSeconds));

            GameLauncherActivity activity = (GameLauncherActivity) context;
            boolean isIgnoring = activity.isIgnoringBatteryOptimizationsWrapper(gameInfo.getPackageName());
            int colorId = isIgnoring ? android.R.color.holo_green_dark : android.R.color.holo_red_dark;

            Drawable icon = holder.batteryOptimizationButton.getDrawable().mutate();
            DrawableCompat.setTint(icon, ContextCompat.getColor(context, colorId));
            holder.batteryOptimizationButton.setImageDrawable(icon);

            holder.batteryOptimizationButton.setOnClickListener(v -> {
                if (!isIgnoring) {
                    // Removed argument passed to this method
                    activity.requestIgnoreBatteryOptimizationsWrapper();
                } else {
                    Toast.makeText(context, "Optimization already disabled.", Toast.LENGTH_SHORT).show();
                }
            });

            holder.launchButton.setOnClickListener(v -> {
                if (activity.dndSwitch.isChecked()) {
                    activity.setDndMode(true);
                }

                boolean vpnEnabled = activity.vpnSwitch.isChecked();

                if (activity.playTimeSwitch.isChecked()) {
                    Intent serviceIntent = new Intent(context, PlayTimeTrackerService.class);
                    serviceIntent.putExtra(PlayTimeTrackerService.EXTRA_PACKAGE_NAME, gameInfo.getPackageName());
                    serviceIntent.putExtra(PlayTimeTrackerService.EXTRA_VPN_ENABLED, vpnEnabled);
                    context.startService(serviceIntent);
                }

                if (vpnEnabled) {
                    Intent vpnIntent = new Intent(context, GameVpnService.class);
                    vpnIntent.setAction(GameVpnService.ACTION_CONNECT);
                    vpnIntent.putExtra(GameVpnService.EXTRA_GAME_PACKAGE, gameInfo.getPackageName());
                    context.startService(vpnIntent);
                }

                Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(gameInfo.getPackageName());
                if (launchIntent != null) {
                    context.startActivity(launchIntent);
                } else {
                    Toast.makeText(context, "Could not launch " + gameInfo.getAppName(), Toast.LENGTH_SHORT).show();
                    if (activity.dndEnabledByApp) {
                        activity.setDndMode(false);
                        activity.dndEnabledByApp = false;
                    }
                }
            });
        }

        private String formatPlayTime(long totalSeconds) {
            if (totalSeconds <= 0) return "No play time tracked";
            long hours = totalSeconds / 3600;
            long minutes = (totalSeconds % 3600) / 60;
            if (hours > 0) return String.format(Locale.getDefault(), "Play time: %dh %dm", hours, minutes);
            return String.format(Locale.getDefault(), "Play time: %dm", minutes);
        }

        @Override
        public int getItemCount() {
            return gameList.size();
        }

        public static class GameViewHolder extends RecyclerView.ViewHolder {
            final ImageView gameIcon;
            final TextView gameName;
            final TextView gamePlayTime;
            final ImageButton batteryOptimizationButton;
            final Button launchButton;

            public GameViewHolder(@NonNull View itemView) {
                super(itemView);
                gameIcon = itemView.findViewById(R.id.game_icon);
                gameName = itemView.findViewById(R.id.game_name);
                gamePlayTime = itemView.findViewById(R.id.game_play_time);
                batteryOptimizationButton = itemView.findViewById(R.id.battery_optimization_button);
                launchButton = itemView.findViewById(R.id.launch_game_button);
            }
        }
    }
}