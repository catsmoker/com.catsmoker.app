package com.catsmoker.app.features.gamingtools.engine

import com.catsmoker.app.features.gamingtools.tools.interventions.GameInterventions

/**
 * The device GameManager CLI (`cmd game`) as Android actually spells it.
 *
 * This class exists because of a real failure: the engine used to send `set` with
 * `--mode performance --fps N` — for two years — and that shape throws
 * `NumberFormatException: For input string: "performance"` on Android 13 (POCO X3 NFC,
 * 2026-10-19, cross-checked while auditing `reference/gamingtools/TMPAD`). `set` parses
 * its flags as an *intervention config*, not a mode; mode selection is its own
 * `cmd game mode` subcommand, and the available modes are the device's answer through
 * `cmd game list-modes`.
 *
 * Everything here is pure: the caller owns the shell. The commands built are the
 * device's own documented subcommands, no `--mode` on `set`.
 *
 * Reference: `reference/gamingtools/TMPAD` (Android 13+, read at dex-string level)
 * carried both the mode/config split and `list-configs`/`list-modes`; this file keeps
 * that split and drops the per-app FPS values below the panel peak its own verdict
 * flags as do-not-copy.
 */
object GameCommandParser {

    /** `cmd game list-modes` answer. */
    data class Modes(
        val current: String,
        val available: List<String>
    ) {
        fun offers(mode: String): Boolean = available.any { it.equals(mode, ignoreCase = true) }
    }

    /**
     * One `[Game Mode:N,…]` block from `cmd game list-configs`.
     *
     * `fps` and `scaling` are nullable on purpose: an entry that carries no value is
     * "not set", and substituting a default would fake a capability the device never
     * reported (the same rule the metrics engine lives by).
     */
    data class Intervention(
        val gameMode: Int,
        val fps: Int?,
        val scaling: Float?
    )

    /** `custom` is GameManager's mode 4 — the row `cmd game set` writes. */
    const val CUSTOM_MODE = 4

    private val CURRENT = Regex("""current mode:\s*([A-Za-z0-9_]+)""", RegexOption.IGNORE_CASE)
    private val AVAILABLE = Regex("""available game modes:\s*\[([^\]]*)]""", RegexOption.IGNORE_CASE)
    private val MODE_BLOCK = Regex("""\[Game Mode:(\d+)([^\]]*)]""")
    private val FPS = Regex("""Fps:(\d+)""")
    private val SCALING = Regex("""Scaling:([0-9.]+)""")
    private val NO_INTERVENTION = Regex("""No intervention found""", RegexOption.IGNORE_CASE)

    /**
     * Parses `cmd game list-modes <pkg>`, or null when the output is not the shape the
     * CLI prints (wrong ROM, wrong package, an error). Null means "could not read the
     * device's answer", never "not supported".
     */
    fun parseModes(output: String): Modes? {
        val current = CURRENT.find(output)?.groupValues?.get(1)?.lowercase() ?: return null
        val available = AVAILABLE.find(output)?.groupValues?.get(1)
            ?.split(',')
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            ?: return null
        return Modes(current = current, available = available)
    }

    /**
     * Parses every `[Game Mode:N,…]` block from `cmd game list-configs <pkg>`.
     *
     * A device with no entry answers "No intervention found" — a real reading, returned
     * as an empty list, not an error.
     */
    fun parseInterventions(output: String): List<Intervention> {
        if (NO_INTERVENTION.containsMatchIn(output)) return emptyList()
        return MODE_BLOCK.findAll(output).mapNotNull { match ->
            val mode = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val body = match.groupValues[2]
            Intervention(
                gameMode = mode,
                fps = FPS.find(body)?.groupValues?.get(1)?.toIntOrNull(),
                scaling = SCALING.find(body)?.groupValues?.get(1)?.toFloatOrNull()
            )
        }.toList()
    }

    /**
     * The block the `cmd game set` fallback writes, if present; otherwise the first
     * block the device reports. Null when there is no entry at all.
     */
    fun parseIntervention(output: String): Intervention? {
        val interventions = parseInterventions(output)
        return interventions.firstOrNull { it.gameMode == CUSTOM_MODE }
            ?: interventions.firstOrNull()
    }

    /** `cmd game mode performance <pkg>` — the mode subcommand, never `set --mode`. */
    fun performanceModeArgs(pkg: String): List<String> =
        listOf("cmd", "game", "mode", "performance", pkg)

    /**
     * The `cmd game set` fallback used only when the `device_config game_overlay`
     * table refuses the write. Carries the panel's peak (never a lower value) and the
     * user's sanitized downscale, so the fallback can never introduce a ceiling the
     * table path would not have set.
     */
    fun customInterventionArgs(pkg: String, maxFps: Int, downscale: Float?): List<String> {
        val factor = GameInterventions.sanitizeDownscale(downscale)
        return buildList {
            add("cmd")
            add("game")
            add("set")
            add("--fps")
            add(maxFps.toString())
            if (factor != null) {
                add("--downscale")
                add(GameInterventions.downscaleText(factor))
            }
            add(pkg)
        }
    }
}
