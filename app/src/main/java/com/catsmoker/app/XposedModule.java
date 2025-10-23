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
        Map<String, String> op13Props = createOP13Props();
        packagesToSpoof.put("com.activision.callofduty.shooter", op13Props);
        packagesToSpoof.put("com.activision.callofduty.warzone", op13Props);
        packagesToSpoof.put("com.garena.game.codm", op13Props);
        packagesToSpoof.put("com.tencent.tmgp.kr.codm", op13Props);
        packagesToSpoof.put("com.vng.codmvn", op13Props);
        packagesToSpoof.put("com.tencent.tmgp.cod", op13Props);
        packagesToSpoof.put("com.tencent.ig", op13Props);
        packagesToSpoof.put("com.pubg.imobile", op13Props);
        packagesToSpoof.put("com.pubg.krmobile", op13Props);
        packagesToSpoof.put("com.rekoo.pubgm", op13Props);
        packagesToSpoof.put("com.vng.pubgmobile", op13Props);
        packagesToSpoof.put("com.tencent.tmgp.pubgmhd", op13Props);
        packagesToSpoof.put("com.dts.freefiremax", op13Props);
        packagesToSpoof.put("com.dts.freefireth", op13Props);
        packagesToSpoof.put("com.epicgames.fortnite", op13Props);
        packagesToSpoof.put("com.ea.gp.fifamobile", op13Props);
        packagesToSpoof.put("com.gameloft.android.ANMP.GloftA9HM", op13Props);
        packagesToSpoof.put("com.madfingergames.legends", op13Props);
        packagesToSpoof.put("com.pearlabyss.blackdesertm", op13Props);
        packagesToSpoof.put("com.pearlabyss.blackdesertm.gl", op13Props);
        packagesToSpoof.put("com.netease.lztgglobal", op13Props);
        packagesToSpoof.put("com.riotgames.league.wildrift", op13Props);
        packagesToSpoof.put("com.riotgames.league.wildrifttw", op13Props);
        packagesToSpoof.put("com.riotgames.league.wildriftvn", op13Props);
        packagesToSpoof.put("com.riotgames.league.teamfighttactics", op13Props);
        packagesToSpoof.put("com.riotgames.league.teamfighttacticstw", op13Props);
        packagesToSpoof.put("com.riotgames.league.teamfighttacticsvn", op13Props);
        packagesToSpoof.put("com.ngame.allstar.eu", op13Props);
        packagesToSpoof.put("com.mojang.minecraftpe", op13Props);
        packagesToSpoof.put("com.YoStar.AetherGazer", op13Props);
        packagesToSpoof.put("com.miHoYo.GenshinImpact", op13Props);
        packagesToSpoof.put("com.garena.game.lmjx", op13Props);
        packagesToSpoof.put("com.epicgames.portal", op13Props);
        packagesToSpoof.put("com.tencent.lolm", op13Props);
        packagesToSpoof.put("jp.konami.pesam", op13Props);
        packagesToSpoof.put("com.ea.gp.apexlegendsmobilefps", op13Props);
        packagesToSpoof.put("com.mobilelegends.mi", op13Props);
        packagesToSpoof.put("com.levelinfinite.hotta.gp", op13Props);
        packagesToSpoof.put("com.supercell.clashofclans", op13Props);
        packagesToSpoof.put("com.vng.mlbbvn", op13Props);
        packagesToSpoof.put("com.levelinfinite.sgameGlobal", op13Props);
        packagesToSpoof.put("com.tencent.tmgp.sgame", op13Props);
        packagesToSpoof.put("com.mobile.legends", op13Props);
        packagesToSpoof.put("com.proximabeta.mf.uamo", op13Props);
        packagesToSpoof.put("com.tencent.KiHan", op13Props);
        packagesToSpoof.put("com.tencent.tmgp.cf", op13Props);
        packagesToSpoof.put("com.tencent.tmgp.gnyx", op13Props);
        packagesToSpoof.put("com.netease.newspike", op13Props);
        packagesToSpoof.put("com.proxima.dfm", op13Props);
        packagesToSpoof.put("com.netease.qrsj", op13Props);
        packagesToSpoof.put("com.h73.jhqyna", op13Props);
        packagesToSpoof.put("com.carxtech.sr", op13Props);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        String packageName = loadPackageParam.packageName;

        if (packagesToSpoof.containsKey(packageName)) {
            Map<String, String> propsToChange = packagesToSpoof.get(packageName);
            if (propsToChange != null) {
                spoofProperties(propsToChange);
                XposedBridge.log("Spoofed " + packageName + " as OnePlus 13");
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

    private static Map<String, String> createOP13Props() {
        Map<String, String> props = new HashMap<>();
        props.put("MANUFACTURER", "OnePlus");
        props.put("MODEL", "CPH2649");
        return props;
    }
}
