package com.catsmoker.app.features.gamingtools.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The read-only vendor-key probe built from BoostX's per-SoC vocabulary
 * (`reference/file-engineering/BoostX-Max-CPU-Performance/Processor Boost Modules`).
 *
 * The scripts themselves are counter-examples (synthetic load, thermal-disable props,
 * no read-back/restore) and stay out of the app. What survives is the key-name
 * vocabulary, and only as a presence probe: a key the device already carries is
 * evidence for a future gated lever, and a key it does not carry is an answer too.
 * Nothing here writes.
 */
class VendorPerformanceKeysTest {

    @Test
    fun catalogsTheKirinExynosAndUnisocNamespacesTheScriptsUse() {
        assertTrue(VendorPerformanceKeys.candidatesFor(VendorPerformanceKeys.Vendor.KIRIN).any { it == "persist.sys.huawei.gpu.turbo" })
        assertTrue(VendorPerformanceKeys.candidatesFor(VendorPerformanceKeys.Vendor.EXYNOS).any { it == "persist.sys.dvfs.level" })
        assertTrue(VendorPerformanceKeys.candidatesFor(VendorPerformanceKeys.Vendor.UNISOC).any { it == "ro.vendor.sprd_sys_performance" })
        assertTrue(VendorPerformanceKeys.candidatesFor(VendorPerformanceKeys.Vendor.MEDIATEK).any { it == "persist.vendor.mtk_fpsgo_v3_support" })
        // No vendor's write-only thermal-disable prop is a candidate: reading it is
        // allowed (a snapshot could want to know), but there is no "boost" path for it.
        assertTrue(VendorPerformanceKeys.boostKeys.none { it.contains("thermal") })
        assertFalse(VendorPerformanceKeys.candidatesFor(VendorPerformanceKeys.Vendor.KIRIN).isEmpty())
    }

    @Test
    fun parsesGetPropOutputAndKeepsOnlyPresentCatalogKeys() {
        val output = """
            [persist.sys.huawei.gpu.turbo]: [1]
            [ro.config.hw_op_perf]: [1]
            [persist.sys.thermal.config]: [0]
            [ro.product.model]: [M2007J20CG]
        """.trimIndent()
        val readings = VendorPerformanceKeys.readPresent(
            output,
            listOf("persist.sys.huawei.gpu.turbo", "persist.sys.hiai.perf.mode", "ro.config.hw_op_perf")
        )
        assertEquals(
            listOf(
                VendorPerformanceKeys.Reading("persist.sys.huawei.gpu.turbo", "1"),
                VendorPerformanceKeys.Reading("ro.config.hw_op_perf", "1")
            ),
            readings
        )
    }

    @Test
    fun blankValuesReadAsPresentButUnset() {
        val readings = VendorPerformanceKeys.readPresent(
            "[persist.sys.dvfs.level]: []",
            listOf("persist.sys.dvfs.level")
        )
        assertEquals(listOf(VendorPerformanceKeys.Reading("persist.sys.dvfs.level", null)), readings)
    }
}
