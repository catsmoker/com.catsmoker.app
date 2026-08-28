package com.catsmoker.app.shared.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The config vocabulary shared by the app and the Xposed module running inside other apps.
 *
 * Every delivery channel passes through these four functions — the ContentProvider, both
 * `Settings.Global` channels and the exported Magisk bundle all render or parse the same
 * `key=value` text — so a parsing slip here is a silently empty or wrong spoof in the target
 * process, where there is no UI to report it.
 */
class LSPosedConfigTest {

    // -------------------------------------------------------- section framing

    @Test
    fun sectionsRoundTripPerPackage() {
        val configs = linkedMapOf(
            "com.tencent.ig" to "ro.product.model=Pixel 8 Pro\nscreen.width=1440\n",
            "com.garena.game.codm" to "ro.product.model=SM-S948B\n"
        )
        val document = LSPosedConfig.renderSections(configs)
        for ((pkg, config) in configs) {
            // trimEnd, because the document's own closing newline lands inside whichever section
            // comes last. Harmless — parseDeviceProps skips blank lines — but not byte-identical.
            assertEquals(
                "Round trip for $pkg",
                config.trimEnd('\n'),
                LSPosedConfig.parseSection(document, pkg)?.trimEnd('\n')
            )
        }
        // What the module actually consumes is identical either way.
        for ((pkg, config) in configs) {
            assertEquals(
                "Parsed props for $pkg",
                LSPosedConfig.parseDeviceProps(config),
                LSPosedConfig.parseDeviceProps(LSPosedConfig.parseSection(document, pkg))
            )
        }
    }

    @Test
    fun aLaterSectionDoesNotBleedIntoAnEarlierOne() {
        val document = LSPosedConfig.renderSections(
            linkedMapOf(
                "com.first" to "ro.product.model=First\n",
                "com.second" to "ro.product.model=Second\n"
            )
        )
        val first = LSPosedConfig.parseSection(document, "com.first")
        assertEquals("ro.product.model=First\n", first)
        assertFalse("First section leaked the second: $first", first!!.contains("Second"))
    }

    @Test
    fun sectionsSurviveCommentsAndBlankLinesInsideTheProfile() {
        // renderConfig's real output opens with a comment and separates the global block with one.
        val config = "# Catsmoker generated profile\n\nro.product.model=Pixel 8 Pro\n"
        val document = LSPosedConfig.renderSections(mapOf("com.tencent.ig" to config))
        assertEquals(
            config.trimEnd('\n'),
            LSPosedConfig.parseSection(document, "com.tencent.ig")?.trimEnd('\n')
        )
    }

    @Test
    fun anAbsentPackageYieldsNullRatherThanAnEmptyProfile() {
        // Null is what lets readConfig fall through to the next channel; an empty map would look
        // like a package that was deliberately opted out.
        val document = LSPosedConfig.renderSections(mapOf("com.first" to "ro.product.model=First\n"))
        assertNull(LSPosedConfig.parseSection(document, "com.unassigned"))
    }

    @Test
    fun parseSectionRejectsBlankInputAndBlankPackage() {
        assertNull(LSPosedConfig.parseSection(null, "com.first"))
        assertNull(LSPosedConfig.parseSection("", "com.first"))
        assertNull(LSPosedConfig.parseSection("   ", "com.first"))
        assertNull(LSPosedConfig.parseSection("[com.first]\nro.product.model=First\n", ""))
    }

    @Test
    fun renderSectionsSkipsEntriesThatWouldProduceAnEmptySection() {
        val document = LSPosedConfig.renderSections(
            linkedMapOf(
                "" to "ro.product.model=Nameless\n",
                "com.blank" to "   ",
                "com.real" to "ro.product.model=Real\n"
            )
        )
        assertEquals("[com.real]\nro.product.model=Real\n", document)
        assertNull(LSPosedConfig.parseSection(document, "com.blank"))
    }

    @Test
    fun anEmptyConfigMapRendersAnEmptyDocument() {
        assertEquals("", LSPosedConfig.renderSections(emptyMap()))
    }

    // ----------------------------------------------------------- device props

    @Test
    fun parseDevicePropsReadsKeyValueLines() {
        val props = LSPosedConfig.parseDeviceProps(
            "ro.product.model=Pixel 8 Pro\nro.product.brand=google\nscreen.density=560\n"
        )
        assertEquals(3, props.size)
        assertEquals("Pixel 8 Pro", props["ro.product.model"])
        assertEquals("google", props["ro.product.brand"])
        assertEquals("560", props["screen.density"])
    }

    @Test
    fun parseDevicePropsIgnoresBlankInput() {
        assertTrue(LSPosedConfig.parseDeviceProps(null).isEmpty())
        assertTrue(LSPosedConfig.parseDeviceProps("").isEmpty())
        assertTrue(LSPosedConfig.parseDeviceProps("\n\n   \n").isEmpty())
    }

    @Test
    fun parseDevicePropsDropsCommentsAndMalformedLines() {
        val props = LSPosedConfig.parseDeviceProps(
            """
            # Catsmoker generated profile
            # ro.product.model=Commented out
            not a property line
            =leading equals
            ro.build.id=
            ro.product.model=Pixel 8 Pro
            """.trimIndent()
        )
        // A commented-out property must stay commented out, not arrive as "# ro.product.model".
        assertEquals(1, props.size)
        assertEquals("Pixel 8 Pro", props["ro.product.model"])
        // A key with no value is the same as absent: renderConfig omits blanks entirely, so
        // "ro.build.id=" would otherwise spoof an empty build ID.
        assertFalse(props.containsKey("ro.build.id"))
    }

    @Test
    fun parseDevicePropsKeepsEqualsSignsInsideTheValue() {
        // Only the first '=' separates; base64 payloads and query-shaped values carry more.
        val props = LSPosedConfig.parseDeviceProps("webview.user_agent=Mozilla/5.0 (a=b;c=d)\n")
        assertEquals("Mozilla/5.0 (a=b;c=d)", props["webview.user_agent"])
    }

    @Test
    fun parseDevicePropsTrimsAroundBothKeyAndValue() {
        val props = LSPosedConfig.parseDeviceProps("  ro.product.model  =  Pixel 8 Pro  \n")
        assertEquals("Pixel 8 Pro", props["ro.product.model"])
    }

    @Test
    fun parseDevicePropsKeepsInsertionOrderAndLetsALaterLineWin() {
        val props = LSPosedConfig.parseDeviceProps(
            "ro.product.brand=first\nro.product.model=Pixel\nro.product.brand=second\n"
        )
        assertEquals(listOf("ro.product.brand", "ro.product.model"), props.keys.toList())
        assertEquals("second", props["ro.product.brand"])
    }

    @Test
    fun parseDevicePropsReadsAFullRenderedProfileShape() {
        // Fingerprints carry '/' and ':' and the safe-mode key carries a comma-separated list;
        // neither may be mangled on the way through.
        val props = LSPosedConfig.parseDeviceProps(
            """
            # Catsmoker generated profile

            ro.build.fingerprint=google/husky/husky:14/AP4A.240101.001/11111111:user/release-keys
            ${LSPosedConfig.KEY_APPLY_SCREEN_METRICS}=1
            ${LSPosedConfig.KEY_SAFE_MODE_PACKAGES}=com.a,com.b

            # Global Settings
            debug.hwui.renderer=skiagl
            """.trimIndent()
        )
        assertEquals(
            "google/husky/husky:14/AP4A.240101.001/11111111:user/release-keys",
            props["ro.build.fingerprint"]
        )
        assertTrue(LSPosedConfig.isFlagEnabled(props[LSPosedConfig.KEY_APPLY_SCREEN_METRICS]))
        assertEquals(
            setOf("com.a", "com.b"),
            LSPosedConfig.parseTargetPackages(props[LSPosedConfig.KEY_SAFE_MODE_PACKAGES])
        )
        assertEquals("skiagl", props["debug.hwui.renderer"])
    }

    // ------------------------------------------------------------------ flags

    @Test
    fun isFlagEnabledAcceptsBothSpellingsAndIsCaseInsensitive() {
        for (enabled in listOf("1", "true", "TRUE", "True")) {
            assertTrue("Should be enabled: '$enabled'", LSPosedConfig.isFlagEnabled(enabled))
        }
        for (disabled in listOf(null, "", "0", "false", "FALSE", "yes", "on", " 1 ")) {
            assertFalse("Should be disabled: '$disabled'", LSPosedConfig.isFlagEnabled(disabled))
        }
    }

    // -------------------------------------------------------- package lists

    @Test
    fun parseTargetPackagesSplitsOnNewlinesAndCommas() {
        val expected = setOf("com.tencent.ig", "com.garena.game.codm", "com.pubg.imobile")
        assertEquals(
            expected,
            LSPosedConfig.parseTargetPackages("com.tencent.ig\ncom.garena.game.codm\ncom.pubg.imobile")
        )
        assertEquals(
            expected,
            LSPosedConfig.parseTargetPackages("com.tencent.ig,com.garena.game.codm,com.pubg.imobile")
        )
        assertEquals(
            expected,
            LSPosedConfig.parseTargetPackages("com.tencent.ig,\ncom.garena.game.codm\n,com.pubg.imobile")
        )
    }

    @Test
    fun parseTargetPackagesTrimsAndDropsBlanks() {
        assertEquals(
            setOf("com.tencent.ig", "com.pubg.imobile"),
            LSPosedConfig.parseTargetPackages("  com.tencent.ig  ,, \n , com.pubg.imobile \n")
        )
    }

    @Test
    fun parseTargetPackagesIsEmptyForBlankInput() {
        assertTrue(LSPosedConfig.parseTargetPackages(null).isEmpty())
        assertTrue(LSPosedConfig.parseTargetPackages("").isEmpty())
        assertTrue(LSPosedConfig.parseTargetPackages("  \n , \n ").isEmpty())
    }

    @Test
    fun parseTargetPackagesDedupesAndKeepsFirstSeenOrder() {
        val packages = LSPosedConfig.parseTargetPackages("com.b,com.a,com.b\ncom.c")
        assertEquals(listOf("com.b", "com.a", "com.c"), packages.toList())
    }
}
