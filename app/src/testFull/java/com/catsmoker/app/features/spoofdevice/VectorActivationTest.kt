package com.catsmoker.app.features.spoofdevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read-only Vector activation evidence (see [VectorActivation]).
 *
 * The POCO proved why every parser below exists: `modules ls` listed CatSmoker as
 * "enabled" while no heartbeat existed (fresh install, never injected into self), so
 * enabled-without-proof must not read Active; and a reinstall wipes the heartbeat while
 * the daemon log still shows today's load, so heartbeat-without-log-proof must not read
 * inactive either. Each test pins one half of that distinction.
 */
class VectorActivationTest {

    private val own = "com.catsmoker.app"

    private val modulesLs = """
        PACKAGE               UID    STATUS
        ------------------------------------
        com.catsmoker.app     10222  enabled
        com.strawing.duckusb  10229  enabled
    """.trimIndent()

    @Test
    fun enabledModuleParsesTrue() {
        assertEquals(true, VectorActivation.parseModulesLs(modulesLs, own))
    }

    @Test
    fun disabledModuleParsesFalse() {
        val out = modulesLs.replace("com.catsmoker.app     10222  enabled", "com.catsmoker.app     10222  disabled")
        assertEquals(false, VectorActivation.parseModulesLs(out, own))
    }

    @Test
    fun missingPackageParsesNullNeverFalse() {
        // Not listed is "unknown", never "disabled": absence from the table must not
        // be reported as a verdict about our module.
        assertNull(VectorActivation.parseModulesLs(modulesLs, "com.example.other"))
    }

    @Test
    fun garbageOutputParsesNull() {
        assertNull(VectorActivation.parseModulesLs("unmatched argument", own))
        assertNull(VectorActivation.parseModulesLs("", own))
    }

    @Test
    fun scopeListsScopedPackages() {
        val out = """
            APP_PACKAGE                         USER_ID
            -------------------------------------------
            com.tencent.ig                      0
            com.catsmoker.app                   0
        """.trimIndent()
        assertEquals(setOf("com.tencent.ig", "com.catsmoker.app"), VectorActivation.parseScopeLs(out))
    }

    @Test
    fun emptyScopeParsesEmptyNotNull() {
        // Header with no rows is an empty scope — a different fact from "could not read".
        val out = "APP_PACKAGE  USER_ID\n---------------------"
        assertEquals(emptySet<String>(), VectorActivation.parseScopeLs(out))
    }

    @Test
    fun scopeErrorParsesNull() {
        assertNull(VectorActivation.parseScopeLs("module unknown"))
        assertNull(VectorActivation.parseScopeLs(""))
    }

    @Test
    fun frameworkVersionParses() {
        val out = "Framework Version: 2.2\nVersion Code: 3080\nAPI Version: 102\n"
        assertEquals("2.2", VectorActivation.parseFrameworkVersion(out))
        assertNull(VectorActivation.parseFrameworkVersion("nothing here"))
    }

    @Test
    fun logOwnLoadSurvivesWipedHeartbeat() {
        // Reinstall wipes our prefs (no heartbeat row anywhere) while the daemon still
        // logged today's load: the log half alone must count as loaded proof.
        val out = "[ 2026-10-13T03:07:21.570  10222: 31041: 31041 I/VectorLegacyBridge ] " +
            "CatsmokerLSP: module loaded in own process, heartbeat recorded\n"
        val proof = VectorActivation.parseLogCat(out)
        assertTrue(proof.ownLoad)
        assertTrue(proof.spoofTargets.isEmpty())
    }

    @Test
    fun logSpoofLinesMapTargetsToModels() {
        val out = "I/VectorLegacyBridge ] CatsmokerLSP: spoofing com.tencent.ig as GM45K\n" +
            "I/VectorLegacyBridge ] CatsmokerLSP: spoofing com.tencent.ig as GM45K\n"
        val proof = VectorActivation.parseLogCat(out)
        assertFalse(proof.ownLoad)
        assertEquals(mapOf("com.tencent.ig" to "GM45K"), proof.spoofTargets)
    }

    @Test
    fun loadedProofIsEitherHalf() {
        val base = VectorActivation.Evidence()
        assertFalse(base.loadedProof)
        assertTrue(base.copy(heartbeatFresh = true).loadedProof)
        assertTrue(base.copy(logOwnLoad = true).loadedProof)
        // Enabled in the manager alone is never loaded proof.
        assertFalse(base.copy(moduleEnabled = true).loadedProof)
    }

    @Test
    fun logLoadsIntoOtherProcessesCountWithoutOwnScope() {
        // The daemon logs every process our module loads into. A load into a game
        // UID proves execution without our own package being in scope.
        val out = "[ 2026-10-10T23:59:01.272    10228:  9392:  9392 V/VectorLegacyBridge ] " +
            "Loading legacy module com.catsmoker.app from /data/app/~~x/base.apk\n" +
            "[ 2026-10-13T03:07:20.084    10222: 31041: 31041 V/VectorLegacyBridge ] " +
            "Loading legacy module com.catsmoker.app from /data/app/~~y/base.apk\n"
        val proof = VectorActivation.parseLogCat(out)
        assertEquals(setOf(10228, 10222), proof.loadUids)
        assertFalse(proof.ownLoad)
        assertTrue(proof.spoofTargets.isEmpty())
    }

    @Test
    fun logLoadsForOtherModulesAreIgnored() {
        val out = "[ 2026-10-10T23:58:02.249     1000:  6172:  6172 D/VectorModuleManager ] " +
            "Loading module com.strawing.duckusb\n"
        val proof = VectorActivation.parseLogCat(out)
        assertTrue(proof.loadUids.isEmpty())
        assertFalse(proof.ownLoad)
    }

    @Test
    fun uidPackageListParses() {
        val out = "package:com.tencent.ig\npackage:com.tencent.ig.xv\n"
        assertEquals(
            setOf("com.tencent.ig", "com.tencent.ig.xv"),
            VectorActivation.parseUidPackages(out)
        )
        assertTrue(VectorActivation.parseUidPackages("").isEmpty())
    }

    @Test
    fun targetLoadsCountAsLoadedProof() {
        val base = VectorActivation.Evidence()
        // A load into any other process proves execution — own scope not required.
        assertTrue(base.copy(loadUids = setOf(10228)).loadedProof)
    }

    @Test
    fun headlineDistinguishesAllSixStates() {
        val base = VectorActivation.Evidence()
        // No privilege / never probed: unknown, never absent.
        assertEquals(VectorActivation.Headline.UNKNOWN, VectorActivation.headline(base))
        // Privileged probe found nothing: absent.
        assertEquals(
            VectorActivation.Headline.FRAMEWORK_ABSENT,
            VectorActivation.headline(base.copy(framework = VectorActivation.Framework.ABSENT))
        )
        val vector = base.copy(framework = VectorActivation.Framework.VECTOR)
        // Manager state unknown: unknown, never disabled.
        assertEquals(VectorActivation.Headline.UNKNOWN, VectorActivation.headline(vector))
        // Explicitly disabled: disabled.
        assertEquals(
            VectorActivation.Headline.DISABLED,
            VectorActivation.headline(vector.copy(moduleEnabled = false))
        )
        // Enabled but nothing ever executed: enabled, never loaded.
        assertEquals(
            VectorActivation.Headline.ENABLED_UNVERIFIED,
            VectorActivation.headline(vector.copy(moduleEnabled = true))
        )
        // Execution proven in a target process: loaded (no own scope needed).
        assertEquals(
            VectorActivation.Headline.ACTIVE,
            VectorActivation.headline(vector.copy(moduleEnabled = true, loadUids = setOf(10228)))
        )
        // Execution proven via heartbeat: loaded.
        assertEquals(
            VectorActivation.Headline.ACTIVE,
            VectorActivation.headline(vector.copy(moduleEnabled = true, heartbeatFresh = true))
        )
    }
}
