package com.catsmoker.app.features.spoofdevice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the device-spoofing method-exclusivity decision: the Xposed framework
 * path (LSPosed or Vector) and the generated Magisk module rewrite the same
 * device identity through different channels, so both being armed at once is
 * a conflict the UI must surface and each switch must confirm explicitly.
 *
 * Nothing here touches the device: detection feeds these pure verdicts
 * (heartbeat for Xposed, `/data/adb/modules` presence for Magisk), and the
 * workflows never auto-remove anything — confirmation only gates the tap.
 */
class SpoofMethodConflictTest {

    @Test
    fun conflictNeedsHeartbeatAndInstalledModule() {
        assertTrue(
            SpoofMethodConflict.isConflict(
                xposedActive = true,
                magisk = SpoofMethodConflict.MagiskModuleStatus.INSTALLED
            )
        )
    }

    @Test
    fun conflictIsFalseWithoutHeartbeat() {
        for (magisk in SpoofMethodConflict.MagiskModuleStatus.entries) {
            assertFalse(
                "$magisk without heartbeat must not conflict",
                SpoofMethodConflict.isConflict(xposedActive = false, magisk = magisk)
            )
        }
    }

    @Test
    fun conflictIsFalseWithoutInstalledModule() {
        val notInstalled = SpoofMethodConflict.MagiskModuleStatus.entries -
            SpoofMethodConflict.MagiskModuleStatus.INSTALLED
        for (magisk in notInstalled) {
            assertFalse(
                "$magisk with heartbeat must not conflict",
                SpoofMethodConflict.isConflict(xposedActive = true, magisk = magisk)
            )
        }
    }

    @Test
    fun assignConfirmsOnlyForNewUsageAgainstInstalledModule() {
        // Clearing an assignment removes Xposed usage — never gated.
        assertFalse(
            SpoofMethodConflict.needsAssignConfirm(
                profileId = null,
                magisk = SpoofMethodConflict.MagiskModuleStatus.INSTALLED
            )
        )
        // A fresh assignment while the Magisk module is installed needs the tap.
        assertTrue(
            SpoofMethodConflict.needsAssignConfirm(
                profileId = "some-profile-id",
                magisk = SpoofMethodConflict.MagiskModuleStatus.INSTALLED
            )
        )
        // Disabled, absent, or unreadable module state never gates assignment.
        for (magisk in SpoofMethodConflict.MagiskModuleStatus.entries -
            SpoofMethodConflict.MagiskModuleStatus.INSTALLED
        ) {
            assertFalse(
                "$magisk must not gate assignment",
                SpoofMethodConflict.needsAssignConfirm(profileId = "some-profile-id", magisk = magisk)
            )
        }
    }

    @Test
    fun generateConfirmsWhenXposedMayBeActive() {
        // Live heartbeat: the other method is definitely armed.
        assertTrue(
            SpoofMethodConflict.needsGenerateConfirm(
                moduleActive = true, hasAssignments = false, hasLadders = false
            )
        )
        // Assignments without a heartbeat are latent, not live — but the next
        // reboot would arm them, so generating the second method still confirms.
        assertTrue(
            SpoofMethodConflict.needsGenerateConfirm(
                moduleActive = false, hasAssignments = true, hasLadders = false
            )
        )
        assertTrue(
            SpoofMethodConflict.needsGenerateConfirm(
                moduleActive = false, hasAssignments = false, hasLadders = true
            )
        )
        // Nothing claimed on the Xposed side: no gate.
        assertFalse(
            SpoofMethodConflict.needsGenerateConfirm(
                moduleActive = false, hasAssignments = false, hasLadders = false
            )
        )
    }

    @Test
    fun magiskPresenceMapsToHonestStates() {
        // Without privilege the directory cannot be read — Unknown, never Absent.
        assertEquals(
            SpoofMethodConflict.MagiskModuleStatus.UNKNOWN,
            SpoofMethodConflict.parseMagiskModuleStatus(
                hasPrivilege = false, dirExists = false, disableExists = false
            )
        )
        assertEquals(
            SpoofMethodConflict.MagiskModuleStatus.ABSENT,
            SpoofMethodConflict.parseMagiskModuleStatus(
                hasPrivilege = true, dirExists = false, disableExists = false
            )
        )
        assertEquals(
            SpoofMethodConflict.MagiskModuleStatus.DISABLED,
            SpoofMethodConflict.parseMagiskModuleStatus(
                hasPrivilege = true, dirExists = true, disableExists = true
            )
        )
        assertEquals(
            SpoofMethodConflict.MagiskModuleStatus.INSTALLED,
            SpoofMethodConflict.parseMagiskModuleStatus(
                hasPrivilege = true, dirExists = true, disableExists = false
            )
        )
    }
}
