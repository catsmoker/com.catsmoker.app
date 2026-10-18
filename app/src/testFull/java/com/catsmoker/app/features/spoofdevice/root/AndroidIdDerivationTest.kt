package com.catsmoker.app.features.spoofdevice.root

import com.catsmoker.app.shared.data.model.LSPosedConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the M146 derivation against vectors computed from the reference JavaScript itself
 * (`reference/spoofdevice/COPG-JSON/webroot/js/copg-data.js`'s `deriveAndroidId`, run with Node
 * on the exact reference file text) so this port cannot silently drift from the reference
 * algorithm. The `séed` vector is the one that distinguishes the reference's charwise low-byte
 * mixing from UTF-8 byte mixing — if this implementation ever "fixes" the encoding, that vector
 * fails first.
 *
 * The `withDerivedIds` contract: literal keys always win, injection happens only for a non-blank
 * seed plus a non-blank package, the seed key itself survives, and the same map instance comes
 * back when nothing was added (the module logs on identity change).
 */
class AndroidIdDerivationTest {

    // ------------------------------------------------------- reference vectors

    @Test
    fun deriveMatchesTheReferenceAlgorithm() {
        assertEquals("432735ad49ddb628", AndroidIdDerivation.derive("0123456789abcdef", "com.example.game"))
        assertEquals("a022d5009e81525f", AndroidIdDerivation.derive("0123456789abcdef", "serial:com.example.game"))
        assertEquals("5156998d66e40b86", AndroidIdDerivation.derive("0123456789abcdef", "org.catsmoker.test"))
        assertEquals("4c55bae3897f3c1f", AndroidIdDerivation.derive("deadbeefcafebabe", "com.example.game"))
        assertEquals("988dad13e797d687", AndroidIdDerivation.derive("0000000000000000", "com.example.game"))
        // Non-ASCII: charwise `code & 0xFF`, exactly like the reference's charCodeAt — not UTF-8.
        assertEquals("1b67eb56f8978c9c", AndroidIdDerivation.derive("séed", "pkg"))
    }

    @Test
    fun deriveIsStableWellFormedAndDistinctAcrossSubjects() {
        val a = AndroidIdDerivation.derive("0123456789abcdef", "com.a")
        assertEquals("stable for the same input", a, AndroidIdDerivation.derive("0123456789abcdef", "com.a"))
        assertEquals("always 16 lower-case hex chars", 16, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        // Subjects are the package and the serial domain prefix: one seed, unrelated values.
        assertNotEquals(a, AndroidIdDerivation.derive("0123456789abcdef", "com.b"))
        assertNotEquals(
            AndroidIdDerivation.derive("0123456789abcdef", "com.a"),
            AndroidIdDerivation.derive("0123456789abcdef", AndroidIdDerivation.serialSubject("com.a"))
        )
        assertNotEquals(
            "a different seed is a different identity",
            a, AndroidIdDerivation.derive("fedcba9876543210", "com.a")
        )
    }

    @Test
    fun anEmptySeedDerivesNothing() {
        assertEquals("", AndroidIdDerivation.derive("", "com.example.game"))
    }

    // ------------------------------------------------------- injection contract

    @Test
    fun withDerivedIdsAddsBothIdsOnlyWhenLiteralsAreAbsent() {
        val props = mapOf(
            LSPosedConfig.KEY_ANDROID_ID_SEED to "0123456789abcdef",
            "ro.product.model" to "GM45K"
        )
        val out = AndroidIdDerivation.withDerivedIds(props, "com.example.game")
        assertEquals("432735ad49ddb628", out["ANDROID_ID"])
        assertEquals("a022d5009e81525f", out["ro.serialno"])
        assertEquals("GM45K", out["ro.product.model"])
        // The seed key itself stays: it is one of our own keys, and readers that ignore it
        // (everything except this injection) must see the same map they would have seen.
        assertEquals("0123456789abcdef", out[LSPosedConfig.KEY_ANDROID_ID_SEED])
    }

    @Test
    fun literalValuesAlwaysWinOverDerivation() {
        val props = mapOf(
            LSPosedConfig.KEY_ANDROID_ID_SEED to "0123456789abcdef",
            "ANDROID_ID" to "deadbeefdeadbeef",
            "ro.serialno" to "R58M12ABCDE"
        )
        val out = AndroidIdDerivation.withDerivedIds(props, "com.example.game")
        assertEquals("deadbeefdeadbeef", out["ANDROID_ID"])
        assertEquals("R58M12ABCDE", out["ro.serialno"])
    }

    @Test
    fun aLiteralOnOneSurfaceDoesNotBlockTheOther() {
        // The editor exposes separate fields with separate publish switches: a literal Android
        // ID must not stop the serial derivation (and vice versa).
        val androidIdSet = AndroidIdDerivation.withDerivedIds(
            mapOf(
                LSPosedConfig.KEY_ANDROID_ID_SEED to "0123456789abcdef",
                "ANDROID_ID" to "deadbeefdeadbeef"
            ),
            "com.example.game"
        )
        assertEquals("deadbeefdeadbeef", androidIdSet["ANDROID_ID"])
        assertEquals("a022d5009e81525f", androidIdSet["ro.serialno"])
    }

    @Test
    fun derivedIdsArePerPackageAndTheSerialUsesItsOwnDomain() {
        val props = mapOf(LSPosedConfig.KEY_ANDROID_ID_SEED to "0123456789abcdef")
        val game = AndroidIdDerivation.withDerivedIds(props, "com.example.game")
        val other = AndroidIdDerivation.withDerivedIds(props, "org.catsmoker.test")
        assertEquals("432735ad49ddb628", game["ANDROID_ID"])
        assertEquals("5156998d66e40b86", other["ANDROID_ID"])
        assertNotEquals(game["ANDROID_ID"], other["ANDROID_ID"])
        assertNotEquals(game["ro.serialno"], other["ro.serialno"])
        // The serial subject carries the documented domain prefix — the value differs from the
        // same package's Android ID, so one seed never leaks two identical identifiers.
        assertNotEquals(game["ANDROID_ID"], game["ro.serialno"])
    }

    @Test
    fun theSameInstanceComesBackWhenNothingCanBeDerived() {
        val noSeed = mapOf("ro.product.model" to "GM45K")
        assertSame(noSeed, AndroidIdDerivation.withDerivedIds(noSeed, "com.example.game"))
        val blankSeed = mapOf(LSPosedConfig.KEY_ANDROID_ID_SEED to "   ")
        assertSame(blankSeed, AndroidIdDerivation.withDerivedIds(blankSeed, "com.example.game"))
        val seed = mapOf(LSPosedConfig.KEY_ANDROID_ID_SEED to "0123456789abcdef")
        assertSame(seed, AndroidIdDerivation.withDerivedIds(seed, ""))
        assertSame(seed, AndroidIdDerivation.withDerivedIds(seed, "   "))
        // Safe mode / never-spoof: readConfig returns empty before this runs, but an empty map
        // must also stay the same instance rather than grow derived entries from nowhere.
        assertSame(emptyMap<String, String>(), AndroidIdDerivation.withDerivedIds(emptyMap(), "com.example.game"))
    }

    @Test
    fun versionOnlyStripKeepsTheSeedAndTheDerivationRunsAfterIt() {
        // The real read path order — applyUserLists first, derivation second: a version-only
        // package must not lose its per-app IDs, and the derived serial must still be a system
        // property so the getprop overlay carries it (ro.serialno) while ANDROID_ID stays out.
        val props = mapOf(
            "ro.product.model" to "GM45K",
            "ro.build.version.release" to "15",
            LSPosedConfig.KEY_ANDROID_ID_SEED to "0123456789abcdef",
            LSPosedConfig.KEY_VERSION_BYPASS_PACKAGES to "com.example.game"
        )
        val gated = LSPosedConfig.applyUserLists(props, "com.example.game")
        assertFalse("ro.build.version.release" in gated)
        assertEquals("15", props["ro.build.version.release"])

        val derived = AndroidIdDerivation.withDerivedIds(gated, "com.example.game")
        assertEquals("432735ad49ddb628", derived["ANDROID_ID"])
        assertEquals("a022d5009e81525f", derived["ro.serialno"])
        assertTrue(LSPosedConfig.isSystemProperty("ro.serialno"))
        assertFalse(LSPosedConfig.isSystemProperty("ANDROID_ID"))
        assertFalse(LSPosedConfig.isSystemProperty(LSPosedConfig.KEY_ANDROID_ID_SEED))
    }

    @Test
    fun theSeedKeyAndTheHardwareFactKeysAreAppOwnKeysNeverSystemProperties() {
        // Guards the bootloop class of mistakes: any of these gaining a `ro.`-shaped spelling
        // would enter the property overlay and the Magisk channel.
        for (key in listOf(
            LSPosedConfig.KEY_ANDROID_ID_SEED,
            LSPosedConfig.KEY_EMULATOR_HIDE,
            LSPosedConfig.KEY_HW_CPU_CORES,
            LSPosedConfig.KEY_HW_RAM_MB,
            LSPosedConfig.KEY_HW_HEAP_MB,
            LSPosedConfig.KEY_HW_LARGE_HEAP_MB
        )) {
            assertFalse(key, LSPosedConfig.isSystemProperty(key))
        }
    }
}
