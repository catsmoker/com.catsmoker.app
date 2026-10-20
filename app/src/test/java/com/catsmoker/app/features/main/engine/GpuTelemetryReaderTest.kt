package com.catsmoker.app.features.main.engine

import com.catsmoker.app.shared.data.model.MetricReadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Behaviour lock for the GPU reader's node selection and its honest status.
 *
 * The device facts behind it (POCO X3 NFC, 2026-10-19): the kgsl nodes exist but are
 * `EACCES` for both the app UID and `shell`, and read fine under root. So the interesting
 * cases are the ones where nothing was read — they must report *why* rather than a zero.
 */
class GpuTelemetryReaderTest {

    private fun node(path: String, kind: GpuTelemetryReader.Kind) = GpuTelemetryReader.Node(path, kind)

    // ------------------------------------------------------------- selection

    @Test
    fun firstParsableNodeWinsPerMetric() {
        val nodes = listOf(
            node("/a/gpu_busy_percentage", GpuTelemetryReader.Kind.UTILIZATION),
            node("/a/gpubusy", GpuTelemetryReader.Kind.BUSY_PAIR),
            node("/a/clock_mhz", GpuTelemetryReader.Kind.CLOCK_MHZ),
            node("/a/devfreq/cur_freq", GpuTelemetryReader.Kind.CLOCK_HZ)
        )
        val reading = GpuTelemetryReader.assemble(
            listOf(
                nodes[0] to "34 %",
                nodes[1] to "25 100",
                nodes[2] to "490",
                nodes[3] to "180000000"
            ),
            privileged = true
        )
        assertEquals(34, reading.utilizationPercent)
        assertEquals(490, reading.clockMhz)
        assertEquals(MetricReadStatus.Ok, reading.status)
    }

    @Test
    fun kgslGpuBusyPercentageIsPreferredOverTheCounterPair() {
        val nodes = listOf(
            node("/a/gpu_busy_percentage", GpuTelemetryReader.Kind.UTILIZATION),
            node("/a/gpubusy", GpuTelemetryReader.Kind.BUSY_PAIR)
        )
        val reading = GpuTelemetryReader.assemble(
            listOf(nodes[0] to "34 %", nodes[1] to "25 100"),
            privileged = true
        )
        assertEquals(34, reading.utilizationPercent)
    }

    @Test
    fun utilizationOnlyReadsStillOk() {
        val nodes = listOf(
            node("/a/gpu_busy_percentage", GpuTelemetryReader.Kind.UTILIZATION),
            node("/a/clock_mhz", GpuTelemetryReader.Kind.CLOCK_MHZ)
        )
        val reading = GpuTelemetryReader.assemble(listOf(nodes[0] to "12", nodes[1] to null), privileged = true)
        assertEquals(12, reading.utilizationPercent)
        assertNull(reading.clockMhz)
        assertEquals(MetricReadStatus.Ok, reading.status)
    }

    // ---------------------------------------------------------------- status

    @Test
    fun nothingReadWithoutPrivilegeIsPrivilegeDenied() {
        val reading = GpuTelemetryReader.assemble(
            listOf(node("/a/gpu_busy_percentage", GpuTelemetryReader.Kind.UTILIZATION) to null),
            privileged = false
        )
        assertNull(reading.utilizationPercent)
        assertEquals(MetricReadStatus.PrivilegeDenied, reading.status)
    }

    @Test
    fun privilegedButNoNodeAtAllIsUnsupported() {
        val reading = GpuTelemetryReader.assemble(
            listOf(node("/a/gpu_busy_percentage", GpuTelemetryReader.Kind.UTILIZATION) to null),
            privileged = true
        )
        assertEquals(MetricReadStatus.Unsupported, reading.status)
    }

    @Test
    fun nodeTextThatIsNotAReadingIsParseFailed() {
        // kgsl's `gpubusy` on a GPU that never accumulated a window: text read, no percentage in it.
        val reading = GpuTelemetryReader.assemble(
            listOf(node("/a/gpubusy", GpuTelemetryReader.Kind.BUSY_PAIR) to "0 0"),
            privileged = true
        )
        assertNull(reading.utilizationPercent)
        assertEquals(MetricReadStatus.ParseFailed, reading.status)
    }

    @Test
    fun aZeroPercentageIsARealReading() {
        val reading = GpuTelemetryReader.assemble(
            listOf(node("/a/gpu_busy_percentage", GpuTelemetryReader.Kind.UTILIZATION) to "0 %"),
            privileged = true
        )
        assertEquals(0, reading.utilizationPercent)
        assertEquals(MetricReadStatus.Ok, reading.status)
    }

    // ----------------------------------------------------------- batch sweep

    @Test
    fun batchOutputAttributesTextToEachNode() {
        val nodes = GpuTelemetryReader.candidates(devfreqRoot = File("does-not-exist"))
        val byPath = nodes.associateBy { it.path }
        val utilization = byPath.getValue("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")
        val counterPair = byPath.getValue("/sys/class/kgsl/kgsl-3d0/gpubusy")
        val clock = byPath.getValue("/sys/class/kgsl/kgsl-3d0/clock_mhz")
        val devfreqClock = byPath.getValue("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq")
        val output = buildString {
            append("##catsmoker /sys/class/kgsl/kgsl-3d0/gpu_busy_percentage\n34 %\n")
            append("##catsmoker /sys/class/kgsl/kgsl-3d0/gpubusy\n25 100\n")
            append("##catsmoker /sys/class/kgsl/kgsl-3d0/clock_mhz\n490\n")
            // A node that printed its marker but no value (a vanished node, `cat` refused).
            append("##catsmoker /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq\n")
        }
        val attributes = GpuTelemetryReader.parseBatchOutput(output, nodes).toMap()
        assertEquals("34 %", attributes[utilization])
        assertEquals("25 100", attributes[counterPair])
        assertEquals("490", attributes[clock])
        assertNull(attributes[devfreqClock])
        // A node the sweep never mentioned stays unread rather than empty-string.
        assertNull(attributes[byPath.getValue("/sys/kernel/ged/hal/gpu_utilization")])
    }

    @Test
    fun batchCommandMentionsEveryNodeAndSwallowsMissingOnes() {
        val nodes = listOf(
            node("/a/gpu_busy_percentage", GpuTelemetryReader.Kind.UTILIZATION),
            node("/b/clock_mhz", GpuTelemetryReader.Kind.CLOCK_MHZ)
        )
        val command = GpuTelemetryReader.batchCommand(nodes)
        assertTrue(command.contains("/a/gpu_busy_percentage"))
        assertTrue(command.contains("/b/clock_mhz"))
        assertTrue(command.contains("2>/dev/null"))
        assertEquals('e', command.first())
        // The sweep must exit 0 even when every node is absent: the last `cat` of an
        // absent node otherwise reports exit 1 and the shell wrapper logs a failed root
        // exec for a read that in fact succeeded (seen on the POCO, 2026-10-20).
        assertTrue(command.endsWith("; true"))
    }

    @Test
    fun missingMarkerLeavesTheNodeUnread() {
        val nodes = listOf(
            node("/a/gpu_busy_percentage", GpuTelemetryReader.Kind.UTILIZATION),
            node("/b/clock_mhz", GpuTelemetryReader.Kind.CLOCK_MHZ)
        )
        val output = "##catsmoker /a/gpu_busy_percentage\n34 %\n"
        val attributes = GpuTelemetryReader.parseBatchOutput(output, nodes).toMap()
        assertEquals("34 %", attributes[nodes[0]])
        assertNull(attributes[nodes[1]])
    }

    // ------------------------------------------------------------- candidates

    @Test
    fun devfreqEnumerationTakesOnlyGpuDirectories() {
        val root = kotlin.io.path.createTempDirectory("gpudevfreq").toFile()
        listOf("gpu", "1c00000.qcom,kgsl-3d0", "gpufreq", "mali", "soc:qcom,l3").forEach {
            File(root, it).mkdirs()
        }
        val dirs = GpuTelemetryReader.devfreqGpuDirs(root).map { it.name }
        assertEquals(listOf("gpu", "gpufreq", "mali"), dirs)
        assertTrue(GpuTelemetryReader.candidates(root).any { it.path.endsWith("gpufreq/cur_freq") })
    }
}
