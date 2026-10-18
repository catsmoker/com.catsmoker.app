package com.catsmoker.app.shared.data.repository

import com.catsmoker.app.shared.data.model.DeviceProfile
import com.catsmoker.app.shared.data.model.LSPosedConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The publish contract of the render filter (M111 per-field kill switches):
 *
 * - A fully filled profile renders every key the toggle vocabulary owns.
 * - Disabling one field drops exactly that field's keys and nothing else — the per-id matrix.
 * - The vocabulary is closed: every id names an editor field, every rendered key has an owner
 *   (or appears on the documented non-toggleable list), and no key is owned twice.
 * - Values survive toggling: switching a field off suppresses publication only.
 *
 * [ProfileRenderer] is a pure object precisely so this contract can be asserted against the
 * real output instead of a duplicated fixture — it used to live on `SpoofRepository`, which
 * carries Android dependencies and cannot be instantiated in JVM tests.
 */
class ProfileRendererTest {

    /**
     * A profile with every field filled, so every renderable key is present. The GPU pair and
     * the two gates are included on purpose: pair-gating and blank-means-off would otherwise
     * hide them from the matrix.
     */
    private fun filledProfile() = DeviceProfile(
        brand = "google",
        manufacturer = "Google",
        model = "Pixel 9 Pro",
        productName = "caiman",
        deviceCode = "caiman",
        board = "caiman",
        hardware = "caiman",
        boardPlatform = "tensor",
        buildFingerprint = "google/caiman/caiman:15/AP4A.250105.002/1234567:user/release-keys",
        buildId = "AP4A.250105.002",
        buildDisplayId = "AP4A.250105.002",
        buildIncremental = "1234567",
        buildRelease = "15",
        buildSdk = 35,
        securityPatch = "2025-01-05",
        screenWidth = 1280,
        screenHeight = 2856,
        screenDensity = 480,
        screenRefreshRate = 120,
        applyScreenMetrics = true,
        operatorAlpha = "T-Mobile",
        operatorNumeric = "310260",
        simOperatorAlpha = "T-Mobile",
        simOperatorNumeric = "310260",
        simCountryIso = "us",
        timezone = "America/New_York",
        locale = "en-US",
        serialNumber = "R58M12ABCDE",
        bootloader = "slider-1.3-12345678",
        androidId = "a1b2c3d4e5f60718",
        gpuVendor = "Qualcomm",
        gpuRenderer = "Adreno (TM) 750",
        imei = "358240051111110",
        meid = "A1000001234567",
        subscriberId = "310260123456789",
        simSerialNumber = "89014103211118510720",
        phoneNumber = "+12025550123",
        gaid = "11111111-2222-3333-4444-555555555555",
        gsfId = "abcdef1234567890",
        mediaDrmId = "0a1b2c3d4e5f6071",
        appSetId = "0f1e2d3c4b5a6978",
        hideEmulatorArtifacts = true,
        hwCpuCores = 8,
        hwRamMb = 12288,
        hwHeapMb = 512,
        hwLargeHeapMb = 1024,
        androidIdSeed = "0123456789abcdef"
    ).apply { applyFallbacks() }

    /** The `key` of every rendered `key=value` line. */
    private fun keysIn(text: String): Set<String> = text.lineSequence()
        .mapNotNull { line ->
            val eq = line.indexOf('=')
            if (eq > 0) line.substring(0, eq) else null
        }
        .toSet()

    private fun render(profile: DeviceProfile): String = ProfileRenderer.render(profile, emptyMap())

    /**
     * Rendered keys that no toggle id owns on purpose: fallback-derived fields the editor
     * never shows (out of scope for the first pass), the CPU/SoC props, and the blank/flag
     * gates (M116's emulator switch included — a gate is its own suppressor, so a publish
     * switch on it would be a second control for the same decision). The coverage guard
     * keeps this list honest in both directions.
     */
    private val nonToggleableKeys = setOf(
        "ro.build.description",
        "ro.build.flavor",
        "ro.build.product",
        "ro.build.characteristics",
        "ro.product.cpu.abi",
        "ro.product.cpu.abilist",
        "ro.product.cpu.abilist64",
        "ro.product.cpu.abilist32",
        "ro.soc.model",
        "ro.soc.manufacturer",
        "webview.user_agent",
        LSPosedConfig.KEY_APPLY_SCREEN_METRICS,
        LSPosedConfig.KEY_SCREEN_REFRESH_RATE,
        LSPosedConfig.KEY_EMULATOR_HIDE
    )

    @Test
    fun everyToggleIdNamesAProfileFieldExceptTheDocumentedGpuPair() {
        // The vocabulary is closed and name-bound: an id that stops matching a field would
        // silently suppress nothing. `gpu` is the one id spanning two fields by design.
        val fields = DeviceProfile::class.java.declaredFields.map { it.name }.toSet()
        val unknown = ProfileToggleFields.ALL - fields - setOf(ProfileToggleFields.GPU)
        assertTrue("ids naming no DeviceProfile field: $unknown", unknown.isEmpty())
    }

    @Test
    fun everyToggleableKeyRendersForAFullyFilledProfile() {
        // "A toggleable field stops rendering" fails here: each id must own live keys.
        val rendered = keysIn(render(filledProfile()))
        ProfileToggleFields.ALL.forEach { id ->
            val owned = ProfileToggleFields.keysFor(id)
            assertFalse("toggle id '$id' owns no keys", owned.isEmpty())
            val missing = owned - rendered
            assertTrue("id '$id' keys missing from the render: $missing", missing.isEmpty())
        }
    }

    @Test
    fun everyRenderedKeyHasAnOwnerOrADocumentedExemption() {
        // The other half of the coverage guard: a rendered key without an owner would survive
        // every kill switch — that is how a new field silently bypasses this feature.
        val rendered = keysIn(render(filledProfile()))
        val owned = ProfileToggleFields.keysOf(ProfileToggleFields.ALL)
        val unowned = rendered - owned - nonToggleableKeys
        assertTrue("rendered keys without an owner: $unowned", unowned.isEmpty())
        assertEquals(
            "an exemption key is also owned — it must be one or the other",
            emptySet<String>(),
            owned intersect nonToggleableKeys
        )
        val missingExempt = nonToggleableKeys - rendered
        assertTrue("documented non-toggleable keys stopped rendering: $missingExempt", missingExempt.isEmpty())
    }

    @Test
    fun eachDisabledIdDropsExactlyItsOwnKeys() {
        // The per-id matrix: suppression is exact — no key leaks through a neighbouring id
        // and no unrelated key is collateral.
        val full = keysIn(render(filledProfile()))
        ProfileToggleFields.ALL.forEach { id ->
            val filtered = keysIn(render(filledProfile().copy(disabledFields = setOf(id))))
            val lost = full - filtered
            val gained = filtered - full
            assertEquals("id '$id' lost the wrong keys", ProfileToggleFields.keysFor(id) intersect full, lost)
            assertTrue("id '$id' added keys", gained.isEmpty())
        }
    }

    @Test
    fun disablingTheGpuPairSuppressesBothGlKeys() {
        val enabled = keysIn(render(filledProfile()))
        assertTrue(LSPosedConfig.KEY_GPU_VENDOR in enabled)
        assertTrue(LSPosedConfig.KEY_GPU_RENDERER in enabled)
        // The pair shares one id, so switching either row off suppresses both keys.
        val disabled = keysIn(render(filledProfile().copy(disabledFields = setOf(ProfileToggleFields.GPU))))
        assertFalse(LSPosedConfig.KEY_GPU_VENDOR in disabled)
        assertFalse(LSPosedConfig.KEY_GPU_RENDERER in disabled)
    }

    @Test
    fun valuesSurviveTogglingAndUnknownIdsAreIgnored() {
        val profile = filledProfile()
        val full = render(profile)
        // Editing a value while it is switched off must not lose it: switching back on
        // publishes the edited value — the filter never rewrites, only omits.
        val edited = profile.copy(model = "Edited Model")
        val offKeys = keysIn(render(edited.copy(disabledFields = setOf(ProfileToggleFields.MODEL))))
        assertTrue(ProfileToggleFields.keysFor(ProfileToggleFields.MODEL).none { it in offKeys })
        assertTrue(render(edited).contains("ro.product.model=Edited Model"))
        assertEquals(full, render(profile.copy(disabledFields = emptySet())))
        // A foreign id (hand-edited import, future vocabulary) suppresses nothing.
        assertEquals(full, render(profile.copy(disabledFields = setOf("no-such-field"))))
        // The global block is not profile surface: the safe-mode list rides along unchanged
        // while an identity field is switched off.
        val withGlobals = ProfileRenderer.render(
            profile.copy(disabledFields = setOf(ProfileToggleFields.MODEL)),
            mapOf(LSPosedConfig.KEY_SAFE_MODE_PACKAGES to "com.example")
        )
        assertTrue("safe-mode list must survive field switches", "com.example" in withGlobals)
        val globalKeys = keysIn(withGlobals)
        assertTrue(ProfileToggleFields.keysFor(ProfileToggleFields.MODEL).none { it in globalKeys })
    }

    @Test
    fun versionBypassListRidesTheGlobalBlockUnfiltered() {
        // The version-only list is store-level, like safe mode: it must appear in every rendered
        // profile and survive field switches (its key belongs to no vocabulary id).
        val rendered = ProfileRenderer.render(
            filledProfile().copy(disabledFields = setOf(ProfileToggleFields.MODEL)),
            mapOf(LSPosedConfig.KEY_VERSION_BYPASS_PACKAGES to "com.tencent.ig")
        )
        assertTrue(
            "version-only list must ride the global block",
            "${LSPosedConfig.KEY_VERSION_BYPASS_PACKAGES}=com.tencent.ig" in rendered
        )
    }

    @Test
    fun hardwareFactKeysRenderOnlyWhileSet() {
        // M117: 0 is the editor's blank — the key must vanish so the hook is never consulted
        // and the target keeps its real reading. A set field publishes its number exactly.
        val off = render(DeviceProfile(model = "GM45K"))
        assertFalse(LSPosedConfig.KEY_HW_CPU_CORES in off)
        assertFalse(LSPosedConfig.KEY_HW_RAM_MB in off)
        assertFalse(LSPosedConfig.KEY_HW_HEAP_MB in off)
        assertFalse(LSPosedConfig.KEY_HW_LARGE_HEAP_MB in off)

        val on = render(DeviceProfile(hwCpuCores = 8, hwRamMb = 12288, hwHeapMb = 512, hwLargeHeapMb = 1024))
        assertTrue("hw.cpu_cores=8" in on)
        assertTrue("hw.ram_mb=12288" in on)
        assertTrue("hw.heap_mb=512" in on)
        assertTrue("hw.large_heap_mb=1024" in on)
    }

    @Test
    fun emulatorHideAndSeedKeysRenderOnlyWhenEnabled() {
        // M116's gate is off by default and M146's seed is blank by default: on a fresh profile
        // neither key exists, which is what keeps both capabilities inert until asked for.
        val off = render(DeviceProfile())
        assertFalse(LSPosedConfig.KEY_EMULATOR_HIDE in off)
        assertFalse(LSPosedConfig.KEY_ANDROID_ID_SEED in off)

        val on = render(DeviceProfile(hideEmulatorArtifacts = true, androidIdSeed = "0123456789abcdef"))
        assertTrue("${LSPosedConfig.KEY_EMULATOR_HIDE}=1" in on)
        assertTrue("${LSPosedConfig.KEY_ANDROID_ID_SEED}=0123456789abcdef" in on)
    }
}
