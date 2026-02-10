package com.catsmoker.app

import android.os.Build
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

class LSPosedModule : IXposedHookLoadPackage {
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        val packageName = lpparam.packageName

        // Activation check for the app itself
        if ("com.catsmoker.app" == packageName) {
            try {
                XposedHelpers.setStaticBooleanField(
                    XposedHelpers.findClass("com.catsmoker.app.RootActivity", lpparam.classLoader),
                    "isModuleActive",
                    true
                )
                XposedBridge.log("$TAG: Module activated for $packageName")
            } catch (e: Throwable) {
                XposedBridge.log("$TAG: Failed to set active flag for CatSmoker App: ${e.message}")
            }
        }

        // Fast check if the package is in our target list
        if (TARGET_PACKAGES.contains(packageName)) {
            spoofDevice(packageName)
        }
    }

    private fun spoofDevice(packageName: String) {
        XposedBridge.log("$TAG: Spoofing $packageName as OnePlus Pad 3")

        for (entry in DEVICE_PROPS.entries) {
            try {
                XposedHelpers.setStaticObjectField(Build::class.java, entry.key, entry.value)
            } catch (e: Throwable) {
                XposedBridge.log("$TAG: Failed to spoof ${entry.key} for $packageName: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "CatSmokerModule"

        private val TARGET_PACKAGES: Set<String> = setOf(
            "com.cpuid.cpu_z",
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
            "com.farlightgames.farlight84",
            "com.farlightgames.farlight84.gray",
            "com.garena.game.df",
            "com.FosFenes.Sonolus",
            "com.netease.yysls",
            "com.innersloth.spacemafia",
            "com.epicgames.portal",
            "com.kiloo.subwaysurf"
        )

        private val DEVICE_PROPS: Map<String, String> = mapOf(
            "MANUFACTURER" to "OnePlus",
            "MODEL" to "OPD2415",
            "BRAND" to "OnePlus",
            "PRODUCT" to "OPD2415",
            "DEVICE" to "OnePlusPad3"
        )
    }
}
