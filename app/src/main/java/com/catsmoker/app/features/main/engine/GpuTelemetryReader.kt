package com.catsmoker.app.features.main.engine

import com.catsmoker.app.features.main.engine.parsers.GpuTelemetryParser
import com.catsmoker.app.shared.data.model.MetricReadStatus
import com.catsmoker.app.system.shell.ShellRunner
import java.io.File

/**
 * Reads GPU utilization and core clock from the vendor sysfs nodes.
 *
 * Two facts the design follows from, both measured on the POCO X3 NFC (SM7150, Android 15,
 * 2026-10-19):
 * - The nodes exist and are world-readable by mode, but SELinux denies both `untrusted_app`
 *   and `shell` (`EACCES` from `cat` for both UIDs, values readable under `su`) — so the
 *   reading is a privilege-gated one and must say so instead of showing a dash.
 * - The vendor vocabulary is a small, closed set per silicon vendor: Qualcomm's kgsl
 *   (`gpu_busy_percentage` `%`, `gpubusy` counters, `clock_mhz`), Mali's `mali0/utilization`
 *   plus its devfreq dir, and MediaTek's GED HAL. The candidate tables below are that set;
 *   a device with none of them reports [MetricReadStatus.Unsupported].
 *
 * Read order per poll: plain file I/O first (free, works wherever the policy allows), then —
 * only when nothing was readable and the app holds root/Shizuku — a single batched `cat`
 * over every candidate in one shell fork. A node in the batch that does not exist prints
 * nothing and costs nothing; the batch exists so eight candidate nodes never mean eight
 * forked processes every five seconds.
 *
 * The [MetricReadStatus] is derived from what actually happened, never from "we expected
 * this device to have a GPU": no value with no privilege is `PrivilegeDenied`, no value with
 * privilege and no node text is `Unsupported`, and node text that read but parsed to nothing
 * is `ParseFailed`.
 */
object GpuTelemetryReader {

    /** One candidate node, and how its text is read. */
    data class Node(val path: String, val kind: Kind)

    enum class Kind { UTILIZATION, BUSY_PAIR, CLOCK_HZ, CLOCK_KHZ, CLOCK_MHZ }

    /**
     * Utilization candidates in probe order. Qualcomm's `gpu_busy_percentage` is the
     * direct percentage; `gpubusy` is the same kernel's counter pair, kept as the fallback
     * for kernels that publish only that; Mali exposes `utilization` under the `mali0`
     * misc device; MediaTek's GED HAL exposes `gpu_utilization`.
     */
    val UTILIZATION_NODES: List<Node> = listOf(
        Node("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", Kind.UTILIZATION),
        Node("/sys/class/kgsl/kgsl-3d0/gpubusy", Kind.BUSY_PAIR),
        Node("/sys/class/misc/mali0/device/utilization", Kind.UTILIZATION),
        Node("/sys/kernel/ged/hal/gpu_utilization", Kind.UTILIZATION)
    )

    /**
     * Clock candidates in probe order. `clock_mhz` is MHz by its own name; the devfreq and
     * `gpuclk` nodes are Hz; GED reports kHz. The unit is declared here, never guessed
     * from the value.
     */
    val CLOCK_NODES: List<Node> = listOf(
        Node("/sys/class/kgsl/kgsl-3d0/clock_mhz", Kind.CLOCK_MHZ),
        Node("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq", Kind.CLOCK_HZ),
        Node("/sys/class/kgsl/kgsl-3d0/gpuclk", Kind.CLOCK_HZ),
        Node("/sys/kernel/ged/hal/current_freq", Kind.CLOCK_KHZ),
        Node("/sys/class/devfreq/gpufreq/cur_freq", Kind.CLOCK_HZ)
    )

    /** The devfreq root the dynamic GPU clock candidates are enumerated under. */
    private val DEVFREQ_ROOT = File("/sys/class/devfreq")

    /** Marker prefix used to attribute each `cat` in the batched privileged sweep. */
    private const val MARKER = "##catsmoker "

    /**
     * The devfreq directories that belong to the GPU, by their own names: ARM's Mali
     * driver registers `*.mali`, some kernels register `gpu`, others `gpufreq`. A directory
     * that merely mentions a GPU elsewhere in its name is not guessed at.
     */
    fun devfreqGpuDirs(root: File = DEVFREQ_ROOT): List<File> =
        runCatching {
            root.listFiles { file ->
                file.isDirectory && file.name in GPU_DEVFREQ_NAMES
            }?.sortedBy { it.name }.orEmpty()
        }.getOrDefault(emptyList())

    private val GPU_DEVFREQ_NAMES = setOf("gpu", "gpufreq", "mali", "gpu_core")

    /** Every node this reader probes: the static tables plus the devfreq-native nodes. */
    fun candidates(devfreqRoot: File = DEVFREQ_ROOT): List<Node> =
        UTILIZATION_NODES +
            CLOCK_NODES +
            devfreqGpuDirs(devfreqRoot).map { Node(File(it, "cur_freq").path, Kind.CLOCK_HZ) }

    /** What one poll produced: the values, and why when one is missing. */
    data class Reading(
        val utilizationPercent: Int?,
        val clockMhz: Int?,
        val status: MetricReadStatus
    )

    /**
     * Assembles a reading from node text, in candidate order: the first node that parses
     * supplies each metric, independently of the other. Pure, so the selection rule is
     * testable without a device.
     *
     * @param privileged whether the caller could have used root/Shizuku for these nodes —
     *   it only shapes the explanation for a missing value, never the value itself.
     */
    fun assemble(values: List<Pair<Node, String?>>, privileged: Boolean): Reading {
        var utilization: Int? = null
        var clock: Int? = null
        var sawText = false
        for ((node, raw) in values) {
            if (!raw.isNullOrBlank()) sawText = true
            when (node.kind) {
                Kind.UTILIZATION -> if (utilization == null) {
                    utilization = GpuTelemetryParser.parseUtilization(raw)
                }
                Kind.BUSY_PAIR -> if (utilization == null) {
                    utilization = GpuTelemetryParser.parseBusyPair(raw)
                }
                Kind.CLOCK_HZ -> if (clock == null) {
                    clock = GpuTelemetryParser.parseClockMhz(raw, GpuTelemetryParser.ClockUnit.HZ)
                }
                Kind.CLOCK_KHZ -> if (clock == null) {
                    clock = GpuTelemetryParser.parseClockMhz(raw, GpuTelemetryParser.ClockUnit.KHZ)
                }
                Kind.CLOCK_MHZ -> if (clock == null) {
                    clock = GpuTelemetryParser.parseClockMhz(raw, GpuTelemetryParser.ClockUnit.MHZ)
                }
            }
        }
        val status = when {
            utilization != null || clock != null -> MetricReadStatus.Ok
            // No value and no privilege: the nodes exist on the kernels this app targets,
            // but SELinux hides them from the app UID — the actionable reason is privilege.
            !privileged -> MetricReadStatus.PrivilegeDenied
            // Privileged and still no text: this kernel exposes none of the vocabulary.
            !sawText -> MetricReadStatus.Unsupported
            // Text arrived but nothing in it was a reading.
            else -> MetricReadStatus.ParseFailed
        }
        return Reading(utilization, clock, status)
    }

    /**
     * Attributes each `cat` in a batched sweep to its node. Text between two markers
     * belongs to the marker that opened it; a node whose marker never appeared was not
     * read at all and stays null.
     */
    fun parseBatchOutput(output: String, nodes: List<Node>): List<Pair<Node, String?>> {
        val result = ArrayList<Pair<Node, String?>>(nodes.size)
        for ((index, node) in nodes.withIndex()) {
            val marker = MARKER + node.path
            val start = output.indexOf(marker)
            if (start < 0) {
                result += node to null
                continue
            }
            val bodyStart = start + marker.length
            val next = nodes.asSequence()
                .drop(index + 1)
                .map { output.indexOf(MARKER + it.path, bodyStart) }
                .firstOrNull { it >= 0 } ?: output.length
            val body = output.substring(bodyStart, next).trim()
            result += node to body.ifEmpty { null }
        }
        return result
    }

    /**
     * The batched privileged sweep: one shell fork for every candidate node.
     *
     * Ends with `; true` because a sweep whose last `cat` hits an absent node would otherwise
     * exit 1, and the shell wrapper logs that as a failed root exec even though every read
     * that could happen did happen (PoCO X3 NFC, 2026-10-20).
     */
    fun batchCommand(nodes: List<Node>): String =
        nodes.joinToString("; ") { node ->
            "echo '$MARKER${node.path}'; cat ${node.path} 2>/dev/null"
        } + "; true"

    /**
     * One poll. Direct file I/O first; when that yields nothing and privilege exists, one
     * batched shell read. Never throws: a diagnostics number is not worth the process.
     */
    suspend fun read(shellRunner: ShellRunner): Reading {
        val nodes = candidates()
        val direct = nodes.map { it to readDirect(it.path) }
        val reading = assemble(direct, privileged = false)
        if (reading.utilizationPercent != null || reading.clockMhz != null) return reading
        if (!shellRunner.hasPrivilege()) return reading
        val output = runCatching { shellRunner.exec(batchCommand(nodes)) }.getOrDefault("")
        if (output.isBlank()) return assemble(direct, privileged = true)
        return assemble(parseBatchOutput(output, nodes), privileged = true)
    }

    /** One node as plain file text, or null when it does not exist / is not readable. */
    private fun readDirect(path: String): String? = runCatching {
        val file = File(path)
        if (!file.canRead()) return null
        file.readText()
    }.getOrNull()
}
