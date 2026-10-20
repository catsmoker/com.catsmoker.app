package com.catsmoker.app.features.spoofdevice.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the property-key alias vocabulary both spoof halves share.
 *
 * The Java hooks and the native channel must answer the same key the same way: a profile that
 * renames the model has to answer `ro.product.vendor.model` too, or a target reading the
 * partition spelling natively sees the real device while its Java reader sees the spoofed one —
 * a disagreement stronger than either value alone. The rules live here once and the native
 * payload is serialized from them, so a divergence is a test failure rather than a silent
 * second implementation.
 */
class PropAliasesTest {

    @Test
    fun directAliasAnswersItsCanonicalValue() {
        val table = PropAliases.expandAliases(mapOf("ro.product.device" to "POCO X3 NFC"))

        assertEquals("POCO X3 NFC", table["ro.build.product"])
        assertEquals("POCO X3 NFC", table["ro.build.device"])
        assertEquals("POCO X3 NFC", table["ro.product.device"])
    }

    @Test
    fun directAliasAbsentWhenTheProfileDoesNotAnswerItsTarget() {
        val table = PropAliases.expandAliases(mapOf("ro.product.model" to "Pixel 8"))

        assertNull(table["ro.build.product"])
        assertTrue(table.keys.none { it in PropAliases.DIRECT })
    }

    @Test
    fun profileKeyBeatsTheAliasThatPointsAtIt() {
        // The profile spells the alias out itself; expansion must not overwrite it.
        val table = PropAliases.expandAliases(
            mapOf("ro.product.device" to "canonical", "ro.build.product" to "authored")
        )

        assertEquals("authored", table["ro.build.product"])
    }

    @Test
    fun expansionKeepsEveryProfileEntry() {
        val props = mapOf("ro.product.model" to "Pixel 8", "ro.build.id" to "TQ3A")
        val table = PropAliases.expandAliases(props)

        assertEquals(2, table.size.coerceAtLeast(2))
        for ((key, value) in props) assertEquals(value, table[key])
    }

    @Test
    fun emptyProfileExpandsToNothing() {
        assertTrue(PropAliases.expandAliases(emptyMap()).isEmpty())
    }

    @Test
    fun suffixRuleAnswersOemPartitionSpellings() {
        assertEquals("ro.product.model", PropAliases.suffixTarget("ro.product.vendor.model"))
        assertEquals("ro.product.model", PropAliases.suffixTarget("ro.product.odm.model"))
        assertEquals("ro.product.name", PropAliases.suffixTarget("ro.product.vendor.name"))
        assertEquals("ro.build.fingerprint", PropAliases.suffixTarget("ro.system.build.fingerprint"))
        assertEquals("ro.build.version.sdk", PropAliases.suffixTarget("ro.vendor.build.version.sdk"))
    }

    @Test
    fun suffixRulesNeverInventAKey() {
        // No rule covers these: a miss means "leave the device's own value alone".
        assertNull(PropAliases.suffixTarget("ro.build.product"))
        assertNull(PropAliases.suffixTarget("ro.build.version.release_or_codename"))
        assertNull(PropAliases.suffixTarget("ro.product.vendor.unknown"))
        assertNull(PropAliases.suffixTarget("persist.sys.model"))
        assertNull(PropAliases.suffixTarget("ro.vendor.product.model"))
    }

    @Test
    fun longerVersionSuffixesAreNotShadowed() {
        // Every `.build.version.*` spelling must answer its own key, never the shorter
        // `.build.id` one a sloppier matcher would land on.
        assertEquals(
            "ro.build.version.security_patch",
            PropAliases.suffixTarget("ro.system.build.version.security_patch")
        )
        assertEquals(
            "ro.build.version.incremental",
            PropAliases.suffixTarget("ro.vendor.build.version.incremental")
        )
        assertEquals("ro.build.id", PropAliases.suffixTarget("ro.system.build.id"))
    }

    @Test
    fun rulesMatchWholeSegmentsOnly() {
        // The suffix carries its dot and the prefix its trailing dot: a near-miss key stays a
        // miss, never a broader match than the Java lookup had.
        assertNull(PropAliases.suffixTarget("ro.product.foo-model"))
        assertNull(PropAliases.suffixTarget("ro.productvendor.model"))
        assertNull(PropAliases.suffixTarget("xro.product.model"))
    }
}
