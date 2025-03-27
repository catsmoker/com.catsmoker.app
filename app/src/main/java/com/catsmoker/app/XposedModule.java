package com.catsmoker.app;

import android.annotation.SuppressLint;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

@SuppressLint("DiscouragedPrivateApi")
@SuppressWarnings("ConstantConditions")
public class XposedModule implements IXposedHookLoadPackage {

    private static final String TAG = XposedModule.class.getSimpleName();

    private static final Map<String, Map<String, String>> packagesToSpoof = new HashMap<>();

    static {
        Map<String, String> op12Props = createOP12Props();
        packagesToSpoof.put("com.activision.callofduty.shooter", op12Props);
        packagesToSpoof.put("com.activision.callofduty.warzone", op12Props);
        packagesToSpoof.put("com.garena.game.codm", op12Props);
        packagesToSpoof.put("com.tencent.tmgp.kr.codm", op12Props);
        packagesToSpoof.put("com.vng.codmvn", op12Props);
        packagesToSpoof.put("com.tencent.tmgp.cod", op12Props);
        packagesToSpoof.put("com.tencent.ig", op12Props);
        packagesToSpoof.put("com.pubg.imobile", op12Props);
        packagesToSpoof.put("com.pubg.krmobile", op12Props);
        packagesToSpoof.put("com.rekoo.pubgm", op12Props);
        packagesToSpoof.put("com.vng.pubgmobile", op12Props);
        packagesToSpoof.put("com.tencent.tmgp.pubgmhd", op12Props);
        packagesToSpoof.put("com.dts.freefiremax", op12Props);
        packagesToSpoof.put("com.dts.freefireth", op12Props);
        packagesToSpoof.put("com.epicgames.fortnite", op12Props);
        packagesToSpoof.put("com.ea.gp.fifamobile", op12Props);
        packagesToSpoof.put("com.gameloft.android.ANMP.GloftA9HM", op12Props);
        packagesToSpoof.put("com.madfingergames.legends", op12Props);
        packagesToSpoof.put("com.pearlabyss.blackdesertm", op12Props);
        packagesToSpoof.put("com.pearlabyss.blackdesertm.gl", op12Props);
        packagesToSpoof.put("com.netease.lztgglobal", op12Props);
        packagesToSpoof.put("com.riotgames.league.wildrift", op12Props);
        packagesToSpoof.put("com.riotgames.league.wildrifttw", op12Props);
        packagesToSpoof.put("com.riotgames.league.wildriftvn", op12Props);
        packagesToSpoof.put("com.riotgames.league.teamfighttactics", op12Props);
        packagesToSpoof.put("com.riotgames.league.teamfighttacticstw", op12Props);
        packagesToSpoof.put("com.riotgames.league.teamfighttacticsvn", op12Props);
        packagesToSpoof.put("com.ngame.allstar.eu", op12Props);
        packagesToSpoof.put("com.mojang.minecraftpe", op12Props);
        packagesToSpoof.put("com.YoStar.AetherGazer", op12Props);
        packagesToSpoof.put("com.miHoYo.GenshinImpact", op12Props);
        packagesToSpoof.put("com.garena.game.lmjx", op12Props);
        packagesToSpoof.put("com.epicgames.portal", op12Props);
        packagesToSpoof.put("com.tencent.lolm", op12Props);
        packagesToSpoof.put("jp.konami.pesam", op12Props);
        packagesToSpoof.put("com.ea.gp.apexlegendsmobilefps", op12Props);
        packagesToSpoof.put("com.mobilelegends.mi", op12Props);
        packagesToSpoof.put("com.levelinfinite.hotta.gp", op12Props);
        packagesToSpoof.put("com.supercell.clashofclans", op12Props);
        packagesToSpoof.put("com.vng.mlbbvn", op12Props);
        packagesToSpoof.put("com.levelinfinite.sgameGlobal", op12Props);
        packagesToSpoof.put("com.tencent.tmgp.sgame", op12Props);
        packagesToSpoof.put("com.mobile.legends", op12Props);
        packagesToSpoof.put("com.proximabeta.mf.uamo", op12Props);
        packagesToSpoof.put("com.tencent.KiHan", op12Props);
        packagesToSpoof.put("com.tencent.tmgp.cf", op12Props);
        packagesToSpoof.put("com.tencent.tmgp.gnyx", op12Props);
        packagesToSpoof.put("com.netease.newspike", op12Props);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        String packageName = loadPackageParam.packageName;

        if (packagesToSpoof.containsKey(packageName)) {
            Map<String, String> propsToChange = packagesToSpoof.get(packageName);
            if (propsToChange != null) {
                spoofProperties(propsToChange);
                XposedBridge.log("Spoofed " + packageName + " as OnePlus 12");
            }
        }
    }

    private static void spoofProperties(Map<String, String> properties) {
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            setPropValue(entry.getKey(), entry.getValue());
        }
    }

    private static void setPropValue(String key, String value) {
        try {
            Log.d(TAG, "Setting property " + key + " to " + value);
            Field field = Build.class.getDeclaredField(key);
            field.setAccessible(true);
            field.set(null, value);
            field.setAccessible(false);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            String errorMessage = "Failed to set property: " + key + " to " + value;
            Log.e(TAG, errorMessage, e);
            XposedBridge.log(errorMessage + "\n" + Log.getStackTraceString(e));
        }
    }

    private static Map<String, String> createOP12Props() {
        Map<String, String> props = new HashMap<>();
        props.put("MANUFACTURER", "OnePlus");
        props.put("MODEL", "PJD110");
        return props;
    }
}