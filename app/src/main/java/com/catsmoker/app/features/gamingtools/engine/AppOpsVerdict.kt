package com.catsmoker.app.features.gamingtools.engine

/**
 * What `cmd appops get <pkg> RUN_IN_BACKGROUND` actually reported, read from the shell's own
 * words rather than from the command having been issued.
 *
 * Device evidence (POCO X3, Android 15 LineageOS, 2026-10-17): a package with no override
 * answers `No operations.` + `Default mode: allow`; after `cmd appops set <pkg>
 * RUN_IN_BACKGROUND ignore` the same command answers `RUN_IN_BACKGROUND: ignore`; after a
 * restore wrote the prior back it answers `RUN_IN_BACKGROUND: default` — the mode word is the
 * whole answer, and an explicit default survives as its own line.
 *
 * Gaming Mode's record ([AppOpsRecord]) stores this parsed word as each package's prior mode
 * and deactivation writes it back; an unparsable answer is null, which both callers read as
 * "do not touch this package" rather than guessing at a prior. Pure so the rule is pinnable by
 * a JVM unit test.
 */
object AppOpsVerdict {

    /** The `RUN_IN_BACKGROUND` op, spelled as `cmd appops` expects it. */
    const val OP_RUN_IN_BACKGROUND = "RUN_IN_BACKGROUND"

    /**
     * Mode words `cmd appops` accepts and reports. Only these are ever stored or written back —
     * a build that reports anything else is treated as unreadable, because restoring an unknown
     * word could set a mode the user never had.
     */
    val KNOWN_MODES = setOf("allow", "ignore", "deny", "default", "foreground")

    private val explicitLine = Regex("RUN_IN_BACKGROUND:\\s*(\\S+)", RegexOption.IGNORE_CASE)

    /**
     * The mode the answer states, or null when it states none that can be trusted.
     *
     * An explicit `RUN_IN_BACKGROUND: <word>` line wins, lowercased and whitelisted through
     * [KNOWN_MODES]. `No operations.` / `Default mode: …` means there is no override — returned
     * as the `default` mode word, because that is exactly what the restore writes and what
     * reading it back afterwards reports. Anything else is null.
     */
    fun parseMode(stdout: String): String? {
        explicitLine.find(stdout)?.groupValues?.get(1)?.lowercase()?.let { word ->
            return word.takeIf { it in KNOWN_MODES }
        }
        val mentionsNoOverride = stdout.contains("no operations", ignoreCase = true) ||
            stdout.contains("default mode", ignoreCase = true)
        return if (mentionsNoOverride) "default" else null
    }
}
