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
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GameLauncherActivity extends AppCompatActivity {

    private RecyclerView gamesRecyclerView;
    private GameAdapter gameAdapter;
    private List<GameInfo> gameList;
    private MaterialSwitch dndSwitch;
    private MaterialSwitch playTimeSwitch;

    private boolean dndEnabledByApp = false;
    private int previousDndMode = -1;

    private TextView networkStatusText;
    private LinearLayout networkSuggestions;
    private Button dataSaverButton;
    private Button netguardButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_game_launcher);

        dndSwitch = findViewById(R.id.dnd_switch);
        playTimeSwitch = findViewById(R.id.play_time_switch);
        gamesRecyclerView = findViewById(R.id.games_recycler_view);
        gamesRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        networkStatusText = findViewById(R.id.network_status_text);
        networkSuggestions = findViewById(R.id.network_suggestions);
        dataSaverButton = findViewById(R.id.data_saver_button);
        netguardButton = findViewById(R.id.netguard_button);

        gameList = new ArrayList<>();
        gameAdapter = new GameAdapter(this, gameList);
        gamesRecyclerView.setAdapter(gameAdapter);

        setupDndSwitch();
        setupPlayTimeSwitch();
        setupNetworkStability();
        scanForGames();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (dndEnabledByApp) {
            setDndMode(false);
            dndEnabledByApp = false;
        }
        updateDndSwitchState();
        updatePlayTimeSwitchState();
        setupNetworkStability();
        gameAdapter.notifyDataSetChanged();
    }

    private void setupNetworkStability() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            networkStatusText.setText("Network: Cannot access network service");
            networkSuggestions.setVisibility(View.GONE);
            return;
        }
        Network activeNetwork = cm.getActiveNetwork();
        if (activeNetwork == null) {
            networkStatusText.setText("Network: Not connected");
            networkSuggestions.setVisibility(View.GONE);
            return;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(activeNetwork);
        if (caps == null) {
            networkStatusText.setText("Network: Not connected");
            networkSuggestions.setVisibility(View.GONE);
            return;
        }

        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            networkStatusText.setText("Network: Wi-Fi");
            networkSuggestions.setVisibility(View.GONE);
        } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            networkStatusText.setText("Network: Mobile Data");
            networkSuggestions.setVisibility(View.VISIBLE);
        } else {
            networkStatusText.setText("Network: Unknown");
            networkSuggestions.setVisibility(View.GONE);
        }

        dataSaverButton.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Intent intent = new Intent(Settings.ACTION_DATA_SAVER_SETTINGS);
                try {
                    startActivity(intent);
                } catch (Exception e) {
                    Toast.makeText(this, "Could not open Data Saver settings.", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(this, "Data Saver settings are not available on this Android version.", Toast.LENGTH_SHORT).show();
            }
        });

        netguardButton.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://netguard.me"));
            try {
                startActivity(intent);
            } catch (Exception e) {
                Toast.makeText(this, "Could not open NetGuard website.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void setupDndSwitch() {
        dndSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !isNotificationPolicyAccessGranted()) {
                requestNotificationPolicyAccess();
                buttonView.setChecked(false);
            }
        });
    }

    private void setupPlayTimeSwitch() {
        playTimeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !hasUsageStatsPermission()) {
                requestUsageStatsPermission();
                buttonView.setChecked(false);
            }
        });
    }

    private void updateDndSwitchState() {
        if (!isNotificationPolicyAccessGranted()) {
            dndSwitch.setChecked(false);
        }
    }

    private void updatePlayTimeSwitchState() {
        if (!hasUsageStatsPermission()) {
            playTimeSwitch.setChecked(false);
        }
    }

    private boolean isNotificationPolicyAccessGranted() {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return notificationManager.isNotificationPolicyAccessGranted();
        }
        return true;
    }

    private void requestNotificationPolicyAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS);
            startActivity(intent);
            Toast.makeText(this, "Please grant Do Not Disturb access", Toast.LENGTH_LONG).show();
        }
    }

    private boolean hasUsageStatsPermission() {
        AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
        return mode == AppOpsManager.MODE_ALLOWED;
    }

    private void requestUsageStatsPermission() {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        startActivity(intent);
        Toast.makeText(this, "Please grant Usage Access permission", Toast.LENGTH_LONG).show();
    }

    private void setDndMode(boolean enable) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || !isNotificationPolicyAccessGranted()) {
            return;
        }

        if (enable) {
            previousDndMode = notificationManager.getCurrentInterruptionFilter();
            // Use integer value 4 for INTERRUPTION_FILTER_ALARMS to avoid build error
            notificationManager.setInterruptionFilter(4);
            dndEnabledByApp = true;
        } else {
            if (previousDndMode != -1) {
                notificationManager.setInterruptionFilter(previousDndMode);
                previousDndMode = -1;
            }
        }
    }

    private void scanForGames() {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        gameList.clear();
        for (ApplicationInfo packageInfo : packages) {
            if (isGame(packageInfo)) {
                String appName = packageInfo.loadLabel(pm).toString();
                String packageName = packageInfo.packageName;
                Drawable icon = packageInfo.loadIcon(pm);
                gameList.add(new GameInfo(appName, packageName, icon));
            }
        }
        gameAdapter.notifyDataSetChanged();
    }

    private boolean isGame(ApplicationInfo appInfo) {
        if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if ((appInfo.flags & ApplicationInfo.FLAG_IS_GAME) == ApplicationInfo.FLAG_IS_GAME) {
                return true;
            }
        }
        if (appInfo.category == ApplicationInfo.CATEGORY_GAME) {
            return true;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Intent intent = getPackageManager().getLaunchIntentForPackage(appInfo.packageName);
            if (intent != null) {
                return intent.hasCategory("android.intent.category.GAME");
            }
        }
        return false;
    }

    private boolean isIgnoringBatteryOptimizations(String packageName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return pm.isIgnoringBatteryOptimizations(packageName);
        }
        return true;
    }

    private void requestIgnoreBatteryOptimizations(String packageName) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent();
                intent.setAction(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                if (intent.resolveActivity(getPackageManager()) != null) {
                    startActivity(intent);
                    Toast.makeText(this, "Please find the game in the list and disable battery optimization.", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Could not find activity to handle battery optimization settings.", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception e) {
                e.printStackTrace();
                Toast.makeText(this, "Could not open battery optimization settings", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Battery optimization is not available on this version of Android.", Toast.LENGTH_SHORT).show();
        }
    }

    // --- Nested Classes ---

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
    }

    public class GameAdapter extends RecyclerView.Adapter<GameAdapter.GameViewHolder> {

        private final Context context;
        private final List<GameInfo> gameList;
        private final SharedPreferences playTimePrefs;

        public GameAdapter(Context context, List<GameInfo> gameList) {
            this.context = context;
            this.gameList = gameList;
            this.playTimePrefs = context.getSharedPreferences("PlayTime", MODE_PRIVATE);
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

                        // Battery Optimization Button
                        updateBatteryOptimizationIcon(holder.batteryOptimizationButton, gameInfo.getPackageName());
                        holder.batteryOptimizationButton.setOnClickListener(v -> {
                            Toast.makeText(context, "Checking battery optimization for " + gameInfo.getAppName(), Toast.LENGTH_SHORT).show();
                            try {
                                boolean isIgnoring = GameLauncherActivity.this.isIgnoringBatteryOptimizations(gameInfo.getPackageName());
                                if (!isIgnoring) {
                                    Toast.makeText(context, "Requesting to disable battery optimization.", Toast.LENGTH_SHORT).show();
                                    GameLauncherActivity.this.requestIgnoreBatteryOptimizations(gameInfo.getPackageName());
                                } else {
                                    Toast.makeText(context, "Battery optimization is already disabled for this game.", Toast.LENGTH_SHORT).show();
                                }
                            } catch (Exception e) {
                                Toast.makeText(context, "Error checking battery optimization status.", Toast.LENGTH_LONG).show();
                            }
                        });

                        // Launch Button
                        holder.launchButton.setOnClickListener(v -> {
                            if (dndSwitch.isChecked()) {
                                GameLauncherActivity.this.setDndMode(true);
                            }
                            if (playTimeSwitch.isChecked()) {
                                Intent serviceIntent = new Intent(context, PlayTimeTrackerService.class);
                                serviceIntent.putExtra(PlayTimeTrackerService.EXTRA_PACKAGE_NAME, gameInfo.getPackageName());
                                context.startService(serviceIntent);
                            }

                            Intent launchIntent = context.getPackageManager().getLaunchIntentForPackage(gameInfo.getPackageName());
                            if (launchIntent != null) {
                                context.startActivity(launchIntent);
                            } else {
                                Toast.makeText(context, "Could not launch " + gameInfo.getAppName(), Toast.LENGTH_SHORT).show();
                                if (dndEnabledByApp) {
                                    GameLauncherActivity.this.setDndMode(false);
                                    dndEnabledByApp = false;
                                }
                            }
                        });
                    }

                    private void updateBatteryOptimizationIcon(ImageButton button, String packageName) {
                        Drawable icon = button.getDrawable().mutate();
                        if (GameLauncherActivity.this.isIgnoringBatteryOptimizations(packageName)) {
                            DrawableCompat.setTint(icon, context.getResources().getColor(android.R.color.holo_green_dark));
                        } else {
                            DrawableCompat.setTint(icon, context.getResources().getColor(android.R.color.holo_red_dark));
                        }
                        button.setImageDrawable(icon);
                    }
        private String formatPlayTime(long totalSeconds) {
            if (totalSeconds <= 0) {
                return "No play time tracked";
            }
            long hours = totalSeconds / 3600;
            long minutes = (totalSeconds % 3600) / 60;
            if (hours > 0) {
                return String.format(Locale.getDefault(), "Play time: %dh %dm", hours, minutes);
            } else {
                return String.format(Locale.getDefault(), "Play time: %dm", minutes);
            }
        }


        @Override
        public int getItemCount() {
            return gameList.size();
        }

        public class GameViewHolder extends RecyclerView.ViewHolder {
            ImageView gameIcon;
            TextView gameName;
            TextView gamePlayTime;
            ImageButton batteryOptimizationButton;
            Button launchButton;

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