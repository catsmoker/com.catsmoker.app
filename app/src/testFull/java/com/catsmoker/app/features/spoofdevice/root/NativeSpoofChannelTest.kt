package com.catsmoker.app.features.spoofdevice.root

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the two payloads the native half parses (`app/src/main/jni/catsmoker_spoof.c`): the
 * `key=value` table and the alias-rule list, plus the state-mask vocabulary both sides share.
 *
 * The C side is a plain line splitter — one entry per line, no escaping, no trimming — so the
 * grammar has to be exactly what the renderer emits. Only the pure renderers are exercised
 * here; the native calls themselves are verified on a device (a JVM host has no bridge).
 */
class NativeSpoofChannelTest {

    @Test
    fun propBlobIsOneKeyValuePairPerLine() {
        val blob = NativeSpoofChannel.renderProps(
            mapOf("ro.product.model" to "Pixel 8", "ro.build.id" to "TQ3A.230805")
        )

        assertEquals("ro.product.model=Pixel 8\nro.build.id=TQ3A.230805", blob)
    }

    @Test
    fun emptyTableRendersAnEmptyBlob() {
        // How a cleared profile empties the native table: the setter replaces it whole.
        assertEquals("", NativeSpoofChannel.renderProps(emptyMap()))
    }

    @Test
    fun aliasRuleBlobRoundTripsTheRuleList() {
        val parsed = NativeSpoofChannel.renderRules().lineSequence().filter { it.isNotEmpty() }
            .map { line ->
                val (left, canonical) = line.split("=", limit = 2)
                val (prefix, suffix) = left.split("|", limit = 2)
                PropAliases.SuffixRule(prefix, suffix, canonical)
            }.toList()

        assertEquals(PropAliases.SUFFIX, parsed)
    }

    @Test
    fun aliasRuleBlobHasOneSeparatorPerField() {
        for (line in NativeSpoofChannel.renderRules().lineSequence().filter { it.isNotEmpty() }) {
            assertEquals(line, 1, line.count { it == '|' })
            assertEquals(line, 1, line.count { it == '=' })
        }
    }

    @Test
    fun stateDescriptionNamesWhatInstalled() {
        assertEquals("none", NativeSpoofChannel.describe(0))
        assertEquals(
            "native_api+prop_get+configured",
            NativeSpoofChannel.describe(
                NativeSpoofChannel.STATE_NATIVE_INIT or
                    NativeSpoofChannel.STATE_PROP_GET or
                    NativeSpoofChannel.STATE_CONFIGURED
            )
        )
    }

    @Test
    fun stateBitsMatchTheNativeMask() {
        // Distinct single bits: the mask is a bitfield, and a duplicate constant would make
        // two different native facts indistinguishable in the log.
        val bits = listOf(
            NativeSpoofChannel.STATE_NATIVE_INIT,
            NativeSpoofChannel.STATE_PROP_GET,
            NativeSpoofChannel.STATE_PROP_READ_CALLBACK,
            NativeSpoofChannel.STATE_PROP_READ,
            NativeSpoofChannel.STATE_GL_GLES2,
            NativeSpoofChannel.STATE_CONFIGURED,
            NativeSpoofChannel.STATE_GL_GLES1
        )

        assertEquals(bits.size, bits.distinct().size)
        for (bit in bits) assertEquals(bit.toString(), 1, Integer.bitCount(bit))
        assertTrue(NativeSpoofChannel.describe(bits.fold(0) { acc, bit -> acc or bit }).isNotEmpty())
    }
}
