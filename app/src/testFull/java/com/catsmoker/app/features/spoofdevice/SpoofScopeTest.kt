package com.catsmoker.app.features.spoofdevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards the install-time Xposed scope (`src/full/res/values/spoof_scope.xml`,
 * consumed by the framework via the manifest `xposedscope` meta-data — the only
 * consumer; no app code reads it at runtime).
 *
 * Two invariants with real consequences:
 *
 * 1. Our own package must NOT be listed. The framework treats recommended-scope
 *    entries as mandatory, so listing ourselves locks CatSmoker as selected in
 *    the manager and forces module loads (and detection) through our own
 *    process. Detection must work without it (see [VectorActivation]).
 * 2. Every requested gaming target must be present exactly as requested, with no
 *    duplicates. The manager offers these as scope candidates; a missing entry
 *    silently removes a game from the candidate set.
 */
class SpoofScopeTest {

    /** The gaming targets the owner requested, verbatim (spelling/case preserved). */
    private val requestedTargets = setOf(
        "com.activision.callofduty.shooter",
        "com.activision.callofudty.warzone",
        "com.ea.gp.fifamobile",
        "com.gameloft.android.ANMP.GloftA9HM",
        "com.madfingergames.legends",
        "com.pearlabyss.blackdesertm",
        "com.pearlabyss.blackdesertm.gl",
        "com.garena.game.codm",
        "com.tencent.tmgp.kr.codm",
        "com.vng.codmvn",
        "com.garena.game.kgvn",
        "com.netease.lztgglobal",
        "com.pubg.imobile",
        "com.pubg.krmobile",
        "com.rekoo.pubgm",
        "com.riotgames.league.wildrift",
        "com.riotgames.league.wildrifttw",
        "com.riotgames.league.wildriftvn",
        "com.riotgames.league.teamfighttactics",
        "com.riotgames.league.teamfighttacticstw",
        "com.riotgames.league.teamfighttacticsvn",
        "com.tencent.ig",
        "com.tencent.tmgp.pubgmhd",
        "com.vng.pubgmobile",
        "vng.games.revelation.mobile",
        "com.ngame.allstar.eu",
        "com.mojang.minecraftpe",
        "com.YoStar.AetherGazer",
        "com.miHoYo.GenshinImpact",
        "com.garena.game.lmjx",
        "com.epicgames.fortnite",
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
        "com.dts.freefiremax",
        "com.dts.freefireth",
        "com.mobile.legends",
        "com.proximabeta.mf.uamo",
        "com.tencent.KiHan",
        "com.tencent.tmgp.cf",
        "com.tencent.tmgp.cod",
        "com.tencent.tmgp.gnyx",
        "com.HoYoverse.hkrpgoversea",
        "com.kurogame.wutheringwaves.global"
    )

    private fun scopeItems(): List<String> {
        val text = File("src/full/res/values/spoof_scope.xml").readText()
        return Regex("""<item>([^<]+)</item>""").findAll(text).map { it.groupValues[1].trim() }.toList()
    }

    @Test
    fun ownPackageIsNotInRecommendedScope() {
        // Recommended-scope entries cannot be deselected in the manager. Our own
        // package here is what locked CatSmoker as selected and made detection
        // depend on our own process being scoped.
        assertFalse(
            "com.catsmoker.app must not be a recommended scope entry",
            scopeItems().contains("com.catsmoker.app")
        )
    }

    @Test
    fun allRequestedTargetsArePresentVerbatim() {
        val items = scopeItems().toSet()
        val missing = requestedTargets - items
        assertTrue("missing scope entries: $missing", missing.isEmpty())
    }

    @Test
    fun scopeHasNoDuplicates() {
        val items = scopeItems()
        assertEquals("duplicated scope entries", items.size, items.toSet().size)
    }

    @Test
    fun scopeEntriesAreValidPackageIds() {
        val valid = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+""")
        val bad = scopeItems().filterNot { valid.matches(it) }
        assertTrue("malformed scope entries: $bad", bad.isEmpty())
    }
}
