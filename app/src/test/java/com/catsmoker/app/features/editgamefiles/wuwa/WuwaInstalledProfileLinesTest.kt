package com.catsmoker.app.features.editgamefiles.wuwa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the installed-profile card rows: every nullable field renders as itself or
 * an em-dash (null means unread, never a placeholder), server/level pairs stay
 * paired, and ini counts list only files that carried settings.
 */
class WuwaInstalledProfileLinesTest {

    private fun fullProfile() = WuwaProfileExtractor.InstalledProfile(
        uid = "123456",
        server = "Europe",
        playerLevel = 70,
        serverLevels = listOf("Europe" to 70, "Asia" to 20),
        lastLoginTime = "2026-10-10 12:00",
        towerFloor = 12,
        weeklyRogueScore = 5000,
        battlePassPurchased = true,
        loopTowerSeason = 3,
        gameVersion = "2.7.0",
        patchVersion = "123",
        launcherVersion = "4",
        language = "en",
        iniSettingCounts = mapOf("Engine.ini" to 186, "Game.ini" to 0),
        log = null,
        dbChannel = "root / Shizuku shell",
        logChannel = "root / Shizuku shell"
    )

    @Test
    fun fullProfileRendersEveryRow() {
        val lines = WuwaProfileExtractor.summaryLines(fullProfile())
        assertTrue(lines.any { it == "uid: 123456" })
        assertTrue(lines.any { it == "server: Europe (70)" })
        assertTrue(lines.any { it == "servers: Europe 70, Asia 20" })
        assertTrue(lines.any { it == "last login: 2026-10-10 12:00" })
        assertTrue(lines.any { it == "tower: 12" })
        assertTrue(lines.any { it == "rogue: 5000" })
        assertTrue(lines.any { it == "battle pass: yes" })
        assertTrue(lines.any { it == "loop season: 3" })
        assertTrue(lines.any { it == "game: 2.7.0 / patch 123 / launcher 4" })
        assertTrue(lines.any { it == "language: en" })
        assertTrue(lines.any { it == "ini: Engine.ini 186" })
        assertTrue(lines.any { it == "log: root / Shizuku shell" })
        assertTrue(lines.any { it == "db: root / Shizuku shell" })
    }

    @Test
    fun unreadFieldsRenderAsDashesNeverPlaceholders() {
        val empty = WuwaProfileExtractor.InstalledProfile(
            uid = null,
            server = null,
            playerLevel = null,
            serverLevels = emptyList(),
            lastLoginTime = null,
            towerFloor = null,
            weeklyRogueScore = null,
            battlePassPurchased = null,
            loopTowerSeason = null,
            gameVersion = null,
            patchVersion = null,
            launcherVersion = null,
            language = null,
            iniSettingCounts = emptyMap(),
            log = null,
            dbChannel = null,
            logChannel = null
        )
        val lines = WuwaProfileExtractor.summaryLines(empty)
        assertTrue(lines.any { it == "uid: —" })
        assertTrue(lines.any { it == "server: —" })
        assertTrue(lines.any { it == "battle pass: —" })
        assertTrue(lines.any { it == "db: unreadable" })
        assertTrue(lines.any { it == "log: no log" })
        // No zero-count ini files listed: absence is not a measurement.
        assertTrue(lines.none { it.startsWith("ini:") })
    }

    @Test
    fun battlePassNoRendersNo() {
        val lines = WuwaProfileExtractor.summaryLines(fullProfile().copy(battlePassPurchased = false))
        assertTrue(lines.any { it == "battle pass: no" })
    }

    @Test
    fun gameRowOmitsMissingVersions() {
        val lines = WuwaProfileExtractor.summaryLines(
            fullProfile().copy(gameVersion = "2.7.0", patchVersion = null, launcherVersion = null)
        )
        assertEquals(listOf("game: 2.7.0"), lines.filter { it.startsWith("game:") })
    }
}
