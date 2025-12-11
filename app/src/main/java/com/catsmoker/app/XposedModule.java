package com.catsmoker.app;

import android.os.Build;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class XposedModule implements IXposedHookLoadPackage {

    private static final String TAG = "XposedModule";

    // Using a Set is more efficient for simple containment checks
    private static final Set<String> TARGET_PACKAGES = new HashSet<>(Arrays.asList(
            "com.activision.callofduty.shooter",
            "com.activision.callofduty.warzone",
            "com.garena.game.codm",
            "com.tencent.tmgp.kr.codm",
            "com.vng.codmvn",
            "com.tencent.tmgp.cod",
            "com.tencent.ig",
            "com.pubg.imobile",
            "com.pubg.krmobile",
            "com.rekoo.pubgm",
            "com.vng.pubgmobile",
            "com.tencent.tmgp.pubgmhd",
            "com.dts.freefiremax",
            "com.dts.freefireth",
            "com.epicgames.fortnite",
            "com.ea.gp.fifamobile",
            "com.gameloft.android.ANMP.GloftA9HM",
            "com.madfingergames.legends",
            "com.pearlabyss.blackdesertm",
            "com.pearlabyss.blackdesertm.gl",
            "com.netease.lztgglobal",
            "com.riotgames.league.wildrift",
            "com.riotgames.league.wildrifttw",
            "com.riotgames.league.wildriftvn",
            "com.riotgames.league.teamfighttactics",
            "com.riotgames.league.teamfighttacticstw",
            "com.riotgames.league.teamfighttacticsvn",
            "com.ngame.allstar.eu",
            "com.mojang.minecraftpe",
            "com.YoStar.AetherGazer",
            "com.miHoYo.GenshinImpact",
            "com.garena.game.lmjx",
            "com.epicgames.portal",
            "com.tencent.lolm",
            "jp.konami.pesam",
            "com.ea.gp.apexlegendsmobilefps",
            "com.mobilelegends.mi",
            "com.levelinfinite.hotta.gp",
            "com.supercell.clashofclans",
            "com.vng.mlbbvn",
            "com.levelinfinite.sgameGlobal",
            "com.tencent.tmgp.sgame",
            "com.mobile.legends",
            "com.mobile.legends.usa",
            "com.proximabeta.mf.uamo",
            "com.tencent.KiHan",
            "com.tencent.tmgp.cf",
            "com.tencent.tmgp.gnyx",
            "com.netease.newspike",
            "com.proxima.dfm",
            "com.netease.qrsj",
            "com.h73.jhqyna",
            "com.carxtech.sr",
            "com.miraclegames.farlight84",
            "com.farlightgames.farlight84.gray",
            "com.garena.game.df",
            "com.FosFenes.Sonolus",
            "com.netease.yysls"
    ));

    // Define the properties to spoof once
    private static final Map<String, String> DEVICE_PROPS = new HashMap<>();

    static {
        // OnePlus 13 Properties
        DEVICE_PROPS.put("MANUFACTURER", "OnePlus");
        DEVICE_PROPS.put("MODEL", "CPH2649");
        // spoof Brand and Product as well for better compatibility
        DEVICE_PROPS.put("BRAND", "OnePlus");
        DEVICE_PROPS.put("PRODUCT", "CPH2649");
        DEVICE_PROPS.put("DEVICE", "OnePlus13");
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        String packageName = loadPackageParam.packageName;

        // Activation check for the app itself
        if ("com.catsmoker.app".equals(packageName)) {
            try {
                XposedHelpers.setStaticBooleanField(
                        loadPackageParam.classLoader.loadClass("com.catsmoker.app.RootActivity"),
                        "isModuleActive",
                        true
                );
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Failed to set active flag for CatSmoker App");
            }
            // Do not return here, as we still want to spoof devices if the app itself is a target.
            // However, typically the app itself is not a target for spoofing.
        }

        // Fast check if the package is in our target list
        if (TARGET_PACKAGES.contains(packageName)) {
            spoofDevice(packageName);
        }
    }

    private void spoofDevice(String packageName) {
        XposedBridge.log(TAG + ": Spoofing " + packageName + " as OnePlus 13");

        for (Map.Entry<String, String> entry : DEVICE_PROPS.entrySet()) {
            try {
                // XposedHelpers handles setAccessible and error handling internally
                XposedHelpers.setStaticObjectField(Build.class, entry.getKey(), entry.getValue());
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": Failed to spoof " + entry.getKey() + " for " + packageName);
            }
        }
    }
}