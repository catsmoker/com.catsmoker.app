package com.catsmoker.app.features.editgamefiles.wuwa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Permanent product requirement: Game Log Analysis, Deploy History and the
 * Convene (gacha) tracker must never appear in the Wuthering Waves File
 * Engineering interface again.
 *
 * This test scans the WuWa integration sources for the identifiers that would
 * resurrect those surfaces, pins the deleted files, and pins the deleted string
 * ids. It is intentionally syntactic: the ban is on the entry points themselves,
 * not on any one behavior. The tuner and the installed profile read Client.log
 * internally for their own axes (`readClientLog`, `WuwaLogDecryptor`,
 * `WuwaLogParser`) — those stay and are NOT in the ban list; the generator's
 * `KuroGachaTools` content path is the game's own plugin entry, also NOT the
 * tracker.
 */
class WuwaRemovedFeaturesTest {

    private val wuwaDir = File(
        "src/main/java/com/catsmoker/app/features/editgamefiles/wuwa"
    )

    private fun source(name: String): String {
        val file = File(wuwaDir, name)
        assertTrue("expected WuWa source missing: $name", file.isFile)
        return file.readText()
    }

    /** Identifiers whose presence would resurrect a removed surface. */
    private val forbidden = listOf(
        // Game Log Analysis entry points and state.
        "analyzeGameLog",
        "WuwaLogAnalysis",
        "logAnalysis",
        "analyzingLog",
        "logAnalysisFailure",
        "WuwaLogImportGate",
        "setImportFromLog",
        "onAnalyzeLog",
        "onImportFromLog",
        "importFromLog",
        "mergeLogCvars",
        "logCvars",
        // The battle-stats reader only ever fed the analysis card.
        "WuwaBattleStats",
        // Deploy History entry points, state and store.
        "WuwaDeployHistoryStore",
        "HistoryRecordRow",
        "historyChannelLabel",
        "historyStatusLabel",
        "formattedHistoryTime",
        "verifyHistoryRecord",
        "deleteHistoryRecord",
        "clearHistory",
        "recordDeploy",
        "verifyRecord",
        "verifyingHistoryId",
        "onVerifyHistory",
        "onDeleteHistory",
        "onClearHistory",
        // The Convene (gacha) tracker: built, never wired, deleted by product
        // decision 2026-10-17 (TODO item 15 — do not re-add).
        "WuwaGacha",
        "GachaFetcher",
        "GachaHistoryStore",
        "fetchConvene",
        "convene",
        "Convene"
    )

    @Test
    fun removedFilesStayDeleted() {
        assertFalse(
            "WuwaDeployHistoryStore.kt must not come back",
            File(wuwaDir, "WuwaDeployHistoryStore.kt").exists()
        )
        assertFalse(
            "WuwaLogAnalysis.kt must not come back",
            File(wuwaDir, "WuwaLogAnalysis.kt").exists()
        )
        assertFalse(
            "WuwaBattleStats.kt must not come back",
            File(wuwaDir, "WuwaBattleStats.kt").exists()
        )
        assertFalse(
            "WuwaGacha.kt must not come back",
            File(wuwaDir, "WuwaGacha.kt").exists()
        )
        assertFalse(
            "WuwaGachaFetcher.kt must not come back",
            File(wuwaDir, "WuwaGachaFetcher.kt").exists()
        )
        assertFalse(
            "WuwaGachaHistoryStore.kt must not come back",
            File(wuwaDir, "WuwaGachaHistoryStore.kt").exists()
        )
        assertFalse(
            "WuwaGachaTest.kt must not come back",
            File("src/test/java/com/catsmoker/app/features/editgamefiles/wuwa/WuwaGachaTest.kt").exists()
        )
    }

    @Test
    fun screenHasNoRemovedEntryPoints() {
        val screen = source("WuwaConfigScreen.kt")
        forbidden.forEach { token ->
            assertFalse("WuwaConfigScreen.kt resurrects $token", screen.contains(token))
        }
    }

    @Test
    fun viewModelHasNoRemovedEntryPoints() {
        val vm = source("WuwaConfigViewModel.kt")
        forbidden.forEach { token ->
            assertFalse("WuwaConfigViewModel.kt resurrects $token", vm.contains(token))
        }
    }

    @Test
    fun managerAndGeneratorHaveNoRemovedEntryPoints() {
        val manager = source("WuwaConfigManager.kt")
        val generator = source("WuWaConfigGenerator.kt")
        forbidden.forEach { token ->
            assertFalse("WuwaConfigManager.kt resurrects $token", manager.contains(token))
            assertFalse("WuWaConfigGenerator.kt resurrects $token", generator.contains(token))
        }
    }

    @Test
    fun gachaStringsStayRemoved() {
        val bannedKeys = listOf(
            "gf_convene",
            "gf_fetch_convene",
            "gf_wuwa_gacha_body",
            "gf_wuwa_cached",
            "gf_gacha_"
        )
        listOf("values", "values-en-rGB", "values-ar", "values-es", "values-zh-rCN").forEach { dir ->
            val strings = File("src/main/res/$dir/strings_gamefiles.xml")
            assertTrue("expected strings file missing: $dir", strings.isFile)
            val text = strings.readText()
            bannedKeys.forEach { key ->
                assertFalse("$dir/strings_gamefiles.xml resurrects $key", text.contains(key))
            }
        }
    }
}
