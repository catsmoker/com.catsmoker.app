package com.catsmoker.app.features.spoofdevice

/**
 * Read-only evidence about Vector (Xposed framework) activation.
 *
 * Why this exists: checking only whether our package is listed as "enabled" in the
 * manager does not prove the framework loaded us — on the POCO both CatSmoker and
 * DuckUSB read "enabled" while CatSmoker had never been injected into its own process
 * (fresh install, no heartbeat). Conversely the heartbeat alone under-reports: it lives
 * in our own prefs, so an app reinstall wipes it while the framework keeps loading us
 * into scoped games. Only several independent facts together tell the story.
 *
 * The mechanism mirrors what DuckUSB (`com.strawing.duckusb`, Bouteillepleine/DuckUSB)
 * does: its "Active" state is not the manager's enabled list but a live Binder bridge
 * handed through its own hook running inside `system_server` (`svcState != null` in its
 * `MainActivity`), plus a hook count and per-caller records. We cannot reuse its bridge,
 * but Vector's own read-only `cli` gives the same classes of evidence for us:
 *
 * - `cli modules ls` — selected/enabled in the manager (says nothing about loading).
 * - `cli scope ls <pkg>` — which processes the daemon will inject into.
 * - `cli log cat` — whether the daemon actually loaded us (`Loading legacy module
 *   com.catsmoker.app`, `CatsmokerLSP: module loaded in own process`) and whether our
 *   hooks actually ran (`CatsmokerLSP: spoofing <pkg> as <model>`).
 * - the own-process heartbeat — same load fact, observed from inside.
 *
 * None of this requires our own package to be in scope. A `Loading legacy module`
 * line for any *other* UID proves our entry point executed in that target process;
 * a `spoofing <pkg> as <model>` line proves the hooks ran there. The heartbeat and
 * the own-process log line are kept only as a backstop for users who scope us by
 * hand — never as a requirement.
 *
 * All probes are read-only and Vector-specific (LSPosed has no such CLI); without root
 * every field stays unknown and the heartbeat remains the only signal, never a guess.
 */
object VectorActivation {

    /** Absolute path: `vector-cli` is not on PATH, only reachable here via root. */
    const val CLI_PATH = "/data/adb/modules/zygisk_vector/cli"

    const val MODULE_DIR = "/data/adb/modules/zygisk_vector"

    /** Which framework, if any, the probes could see. */
    enum class Framework {
        /** No privilege to look, or the look has not run yet. */
        UNKNOWN,

        /** Vector's Zygisk module directory / CLI answers. */
        VECTOR,

        /** Privileged look found no Vector installation. */
        ABSENT
    }

    /**
     * One read of every available signal. Nulls mean "could not be checked", never "no":
     * an unchecked framework is not an absent one, and an unread scope is not an empty one.
     */
    data class Evidence(
        val framework: Framework = Framework.UNKNOWN,
        /** Device text from `cli status` (e.g. "2.2"), null when unread. */
        val frameworkVersion: String? = null,
        /** Our row in `cli modules ls`: true = enabled, false = disabled, null = unknown. */
        val moduleEnabled: Boolean? = null,
        /** Packages in our `cli scope ls` output, null when unread. */
        val scopePackages: Set<String>? = null,
        /**
         * Whether our own package is among [scopePackages]. Informational only: since
         * our package left the recommended scope, absence here is the normal state and
         * nothing in detection depends on it.
         */
        val selfInScope: Boolean = false,
        /** Own-process heartbeat fresh (reinstall wipes it — absence proves nothing alone). */
        val heartbeatFresh: Boolean = false,
        /** Daemon log shows our module loaded into our own process this log window. */
        val logOwnLoad: Boolean = false,
        /**
         * UIDs our module was loaded into per the daemon log, any process. A UID other
         * than our own proves our entry point executed in a target process without our
         * own package being scoped.
         */
        val loadUids: Set<Int> = emptySet(),
        /**
         * Target packages our module provably loaded into (daemon log UIDs resolved
         * via `pm list packages --uid`, own UID excluded). Non-empty means execution
         * in a real target — no own-scope entry required.
         */
        val loadedTargets: Set<String> = emptySet(),
        /** Daemon log shows our hooks ran: target package to spoofed model. */
        val logSpoofTargets: Map<String, String> = emptyMap()
    ) {
        /**
         * Loaded proof from any side: inside (heartbeat), own-process log line, or a
         * load into any other process. This is what the status card answers with —
         * enabled-without-proof and proof-without-enabled are both reported honestly
         * by the detail lines.
         */
        val loadedProof: Boolean get() = heartbeatFresh || logOwnLoad || loadUids.isNotEmpty()
    }

    /**
     * The single headline the status card shows. Every state is told apart: in
     * particular ENABLED_UNVERIFIED (manager says on, nothing ever executed) is never
     * presented as ACTIVE (execution proven), and UNKNOWN is never presented as absent.
     */
    enum class Headline {
        /** No privilege to probe, or the probe never ran — not a verdict. */
        UNKNOWN,

        /** A privileged probe found no Vector installation. */
        FRAMEWORK_ABSENT,

        /** The framework is there and our module row reads disabled. */
        DISABLED,

        /** The framework is there, our module reads enabled, but no load was observed. */
        ENABLED_UNVERIFIED,

        /** Our entry point provably executed (any process) or hooks provably ran. */
        ACTIVE
    }

    /** Resolves one [Headline] from [evidence]; see each enum entry for the contract. */
    fun headline(evidence: Evidence): Headline = when {
        evidence.framework != Framework.VECTOR -> when (evidence.framework) {
            Framework.ABSENT -> Headline.FRAMEWORK_ABSENT
            else -> Headline.UNKNOWN
        }
        evidence.moduleEnabled == false -> Headline.DISABLED
        evidence.loadedProof || evidence.logSpoofTargets.isNotEmpty() -> Headline.ACTIVE
        evidence.moduleEnabled == true -> Headline.ENABLED_UNVERIFIED
        else -> Headline.UNKNOWN
    }

    /**
     * Parses the plain-text `cli modules ls` table (`PACKAGE UID STATUS` rows).
     *
     * @return our status, or null when our package has no row (not installed as a module
     *   or the output was unreadable) — never a default.
     */
    fun parseModulesLs(output: String, ownPackage: String): Boolean? {
        var seen = false
        var enabled: Boolean? = null
        for (line in output.lineSequence()) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 3) continue
            val pkg = parts[0]
            if (pkg == "PACKAGE" || pkg.startsWith("---")) continue
            if (pkg != ownPackage) continue
            seen = true
            when {
                parts[2].equals("enabled", ignoreCase = true) -> enabled = true
                parts[2].equals("disabled", ignoreCase = true) -> enabled = false
            }
        }
        return if (seen) enabled else null
    }

    /**
     * Parses the plain-text `cli scope ls <pkg>` table (`APP_PACKAGE USER_ID` rows).
     *
     * @return the scoped packages, or null when no table header was found (command failed
     *   or the module is unknown) — an empty table parses to an empty set, which is a
     *   different fact from "could not read".
     */
    fun parseScopeLs(output: String): Set<String>? {
        var tableSeen = false
        val pkgs = LinkedHashSet<String>()
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("APP_PACKAGE")) {
                tableSeen = true
                continue
            }
            if (trimmed.startsWith("---")) continue
            if (!tableSeen) continue
            val pkg = trimmed.split(Regex("\\s+")).firstOrNull() ?: continue
            if (!pkg.contains('.')) continue
            pkgs.add(pkg)
        }
        return if (tableSeen) pkgs else null
    }

    /** Framework version from the plain-text `cli status` output, or null when absent. */
    fun parseFrameworkVersion(output: String): String? {
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("Framework Version:")) {
                return trimmed.substringAfter(':').trim().takeIf { it.isNotEmpty() }
            }
        }
        return null
    }

    /** Load and hook-execution proof from `cli log cat` output. */
    data class LogProof(
        val ownLoad: Boolean,
        /** UIDs from `Loading legacy module` lines for our module, any process. */
        val loadUids: Set<Int>,
        val spoofTargets: Map<String, String>
    )

    fun parseLogCat(output: String, ownPackage: String = OWN_PACKAGE): LogProof {
        var ownLoad = false
        val uids = LinkedHashSet<Int>()
        val targets = LinkedHashMap<String, String>()
        for (line in output.lineSequence()) {
            if (line.contains("CatsmokerLSP: module loaded in own process")) ownLoad = true
            LOADING_LINE.find(line)?.let { match ->
                if (match.groupValues[2] == ownPackage) {
                    match.groupValues[1].toIntOrNull()?.let { uids += it }
                }
            }
            SPOOF_LINE.find(line)?.let { targets[it.groupValues[1]] = it.groupValues[2] }
        }
        return LogProof(ownLoad, uids, targets)
    }

    /**
     * Parses `pm list packages --uid <uid>` output (`package:<name>` lines) into the
     * package names behind a UID from a daemon load line, so "loaded" can name the
     * target process instead of waving a number.
     */
    fun parseUidPackages(output: String): Set<String> {
        val pkgs = LinkedHashSet<String>()
        for (line in output.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("package:")) {
                trimmed.removePrefix("package:").trim().takeIf { it.isNotEmpty() }?.let { pkgs += it }
            }
        }
        return pkgs
    }

    /** Our own package — the module loading lines name it explicitly. */
    const val OWN_PACKAGE = "com.catsmoker.app"

    /**
     * Matches daemon load lines, capturing the module package and the UID of the
     * process being injected: `[ … 10228: 9392: 9392 V/VectorLegacyBridge ]
     * Loading legacy module com.catsmoker.app from …`.
     */
    private val LOADING_LINE =
        Regex("""^\[\s*\S+\s+(\d+):\s*\d+:\s*\d+\s+\S+\s*\].*Loading legacy module (\S+)""")

    /** Matches our own `XposedBridge.log("$TAG: spoofing <pkg> as <model>")` daemon lines. */
    private val SPOOF_LINE = Regex("""CatsmokerLSP: spoofing (\S+) as (\S+)""")
}
