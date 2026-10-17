package com.catsmoker.app.features.gamingtools.tools.dns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Android DNS channel against the reference DNS changer
 * (`reference/pubg-gfx-tools/emulator-gfx-tool/src/other.py`, preset table at lines 41-46;
 * adapter apply + cache flush in `app_functions.py`, lines 364-380).
 *
 * The reference is a Windows-only tool: it writes plain-IP resolvers into every network
 * adapter (`SetDNSServerSearchOrder`) and flushes the DNS cache. That half has no Android
 * transfer — modern Android resolves through `netd`, never through adapter IPs — and is
 * deliberately not ported. What transfers is the preset-picker shape (named providers +
 * apply + effect check), which [DnsFeature] carries via Private DNS with read-back and
 * active-server proof. These tests pin that the transferable set survived:
 */
class DnsFeatureTest {

    // ------------------------------------------------------------ mode mapping

    @Test
    fun modeFromRoundTripsEverySettingValue() {
        assertEquals(DnsFeature.Mode.OFF, DnsFeature.Mode.from("off"))
        assertEquals(DnsFeature.Mode.AUTOMATIC, DnsFeature.Mode.from("opportunistic"))
        assertEquals(DnsFeature.Mode.PROVIDER, DnsFeature.Mode.from("hostname"))
    }

    @Test
    fun modeFromRejectsForeignValues() {
        assertNull(DnsFeature.Mode.from(null))
        assertNull(DnsFeature.Mode.from(""))
        assertNull(DnsFeature.Mode.from("automatic"))
        assertNull(DnsFeature.Mode.from("1.1.1.1"))
    }

    // ------------------------------------------------------------ preset coverage

    private fun providerById(id: String): DnsFeature.Provider =
        DnsFeature.PROVIDERS.first { it.id == id }

    @Test
    fun providersCoverTheReferencePresetSet() {
        // The reference presets with an Android-transferable form: each plain-IP pair gains
        // the operator's published DNS-over-TLS hostname, which is what Private DNS needs.
        val cloudflare = providerById("cloudflare")
        assertEquals("one.one.one.one", cloudflare.hostname)
        assertTrue(cloudflare.addresses.containsAll(listOf("1.1.1.1", "1.0.0.1")))

        val google = providerById("google")
        assertEquals("dns.google", google.hostname)
        assertTrue(google.addresses.containsAll(listOf("8.8.8.8", "8.8.4.4")))

        val quad9 = providerById("quad9")
        assertEquals("dns.quad9.net", quad9.hostname)
        assertTrue(quad9.addresses.containsAll(listOf("9.9.9.9", "149.112.112.112")))
    }

    @Test
    fun everyProviderIsFullySpecified() {
        assertTrue(DnsFeature.PROVIDERS.isNotEmpty())
        val ids = DnsFeature.PROVIDERS.map { it.id }
        assertEquals("provider ids must be unique: $ids", ids.size, ids.toSet().size)
        for (provider in DnsFeature.PROVIDERS) {
            assertTrue("${provider.id} needs a label", provider.label.isNotBlank())
            assertTrue("${provider.id} needs a hostname", provider.hostname.isNotBlank())
            assertTrue("${provider.id} needs addresses", provider.addresses.isNotEmpty())
            assertTrue("${provider.id} needs a note", provider.note.isNotBlank())
        }
    }

    @Test
    fun providerHostnamesAreTlsNamesNeverBareIps() {
        // Private DNS validates the server certificate against the hostname, so a bare IP
        // would fail closed (no resolution at all). The reference's plain-IP adapter entries
        // must therefore never appear here as hostnames.
        val ipv4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")
        for (provider in DnsFeature.PROVIDERS) {
            assertTrue(
                "${provider.id} hostname must be a TLS name, not a bare IP: ${provider.hostname}",
                !ipv4.matches(provider.hostname)
            )
        }
    }
}
