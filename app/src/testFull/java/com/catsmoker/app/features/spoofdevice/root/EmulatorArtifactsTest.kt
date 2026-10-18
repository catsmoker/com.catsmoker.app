package com.catsmoker.app.features.spoofdevice.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the M116 emulator-artifact predicate: the reference's exact paths and keywords, plus the
 * deliberate subtree bound the reference does not have (keyword matching only under system
 * roots), so a user file cannot be hidden just for containing "vbox".
 *
 * The two `File`-layer hooks that consume this predicate (`exists`, the three `listFiles`
 * overloads) need a live Xposed runtime; their filtering decisions are pinned here as the pure
 * predicate, and the module KDoc states the uncovered native surface.
 */
class EmulatorArtifactsTest {

    @Test
    fun everyExactReferencePathIsHidden() {
        for (path in EmulatorArtifacts.EXACT_PATHS) {
            assertTrue("$path must be hidden", EmulatorArtifacts.isHidden(path))
        }
    }

    @Test
    fun keywordPathsUnderTheSystemRootsAreHidden() {
        for (path in listOf(
            "/dev/socket/qemud",
            "/dev/goldfish_pipe",
            "/sys/devices/virtual/misc/goldfish_sync",
            "/system/lib64/libgoldfish.so",
            "/vendor/etc/init/ranchu.rc",
            "/product/lib/libvboxguest.so",
            "/odm/etc/qemu.conf",
            "/proc/sys/qemu/boot_mode"
        )) {
            assertTrue("$path must be hidden", EmulatorArtifacts.isHidden(path))
        }
    }

    @Test
    fun userFilesOutsideTheScanRootsStayVisible() {
        // The reference's bare substring test would hide all of these; the bound keeps a
        // spoof off the user's own storage.
        for (path in listOf(
            "/sdcard/notes/vbox.txt",
            "/storage/emulated/0/Pictures/goldfish.jpg",
            "/data/user/0/com.example/files/qemu_plans.md",
            "/data/local/tmp/ranchu_test",
            "/mnt/media_rw/qemu",
            "/"
        )) {
            assertFalse("$path must stay visible", EmulatorArtifacts.isHidden(path))
        }
    }

    @Test
    fun nearMissesAndBoundariesStayVisible() {
        // Not a prefix of a scan root, no keyword, or a scan root itself: all real.
        for (path in listOf(
            "",
            "/dev",
            "/system",
            "/devious/qemu_file",       // "/dev..." prefix but not "/dev/..."
            "/proc/cpuinfo",
            "/system/lib/libc.so",
            "/system/bin/linker64",
            "/dev/null",
            "/dev/urandom"
        )) {
            assertFalse("$path must stay visible", EmulatorArtifacts.isHidden(path))
        }
    }

    @Test
    fun matchingIsCaseInsensitiveLikeTheReference() {
        assertTrue(EmulatorArtifacts.isHidden("/DEV/QEMU_PIPE"))
        assertTrue(EmulatorArtifacts.isHidden("/System/Lib64/libGoldfish.so"))
        assertTrue(EmulatorArtifacts.isHidden("/SYS/DEVICES/VIRTUAL/MISC/GOLDFISH_PIPE"))
    }

    @Test
    fun theBoundsAreTheDocumentedSystemSubtrees() {
        // The bound is the policy: keep it explicit so a widened root set is a visible choice.
        assertEquals(
            listOf("/dev", "/proc", "/sys", "/system", "/vendor", "/product", "/odm"),
            EmulatorArtifacts.SCAN_ROOTS
        )
        assertEquals(
            listOf("goldfish", "ranchu", "vbox", "qemu"),
            EmulatorArtifacts.KEYWORDS
        )
    }
}
