package com.catsmoker.app

object LSPosedConfig {
    const val PREFS_NAME = "lsposed_prefs"
    const val KEY_ENABLED = "lsposed_enabled"
    const val KEY_TARGET_PACKAGES = "lsposed_target_packages"
    const val KEY_DEVICE_PROPS = "lsposed_device_props"

    val DEFAULT_TARGET_PACKAGES: Set<String> = setOf(
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

    val DEFAULT_DEVICE_PROPS: Map<String, String> = mapOf(
        "MANUFACTURER" to "OnePlus",
        "MODEL" to "OPD2415",
        "BRAND" to "OnePlus",
        "PRODUCT" to "OPD2415",
        "DEVICE" to "OnePlusPad3"
    )
}
