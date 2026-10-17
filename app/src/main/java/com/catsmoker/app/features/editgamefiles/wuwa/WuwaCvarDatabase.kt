package com.catsmoker.app.features.editgamefiles.wuwa

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The known-CVar database behind the INI hygiene pass: which keys the engine knows, which it
 * monitors, and each monitored key's game default.
 *
 * Ported from `reference/gamingtools/WuWa-Config-Android-main/.../config/CvarDatabase.kt`,
 * read in full before this file was written. Three deliberate divergences:
 * - No coroutine-scope leak: the reference's fire-and-forget `loadScope` is gone — loading is
 *   an explicit suspend `load()` the caller drives once (the ViewModel at startup), and every
 *   read before that returns empty rather than triggering hidden I/O.
 * - No log repository / categorizer dependency: categorization (`CvarCategorizer`,
 *   `buildCvarDetails`) belongs to the accepted/rejected report surface, not to hygiene.
 * - The assets are this app's copies under `assets/wuwa_cvars/` (same three files, same
 *   line shapes); the pure passes below take their sets as parameters so unit tests never
 *   touch assets.
 */
class WuWaCvarDatabase(private val context: Context) {

    @Volatile
    private var allCvars: Set<String>? = null

    @Volatile
    private var monitoredCvars: Set<String>? = null

    @Volatile
    private var defaultValues: Map<String, String>? = null

    private val loadMutex = Mutex()

    /** Loads the three asset tables once; concurrent callers single-flight on the mutex. */
    suspend fun load() = loadMutex.withLock {
        withContext(Dispatchers.IO) {
            if (allCvars != null) return@withContext
            val all = context.assets.open("$ASSET_DIR/libUE4_cvars.txt").bufferedReader().readLines()
                .map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
            val monitored = context.assets.open("$ASSET_DIR/config_monitor_cvars.txt").bufferedReader().readLines()
                .map { it.trim().lowercase() }.filter { it.isNotBlank() }.toSet()
            val defaults = context.assets.open("$ASSET_DIR/config_monitor_values.txt").bufferedReader().readLines()
                .mapNotNull { line ->
                    val trimmed = line.trim()
                    if (trimmed.isBlank()) return@mapNotNull null
                    val eq = trimmed.indexOf('=')
                    if (eq <= 0) return@mapNotNull null
                    trimmed.substring(0, eq).trim().lowercase() to trimmed.substring(eq + 1).trim()
                }.toMap()
            monitoredCvars = monitored
            defaultValues = defaults
            allCvars = all
        }
    }

    val isLoaded: Boolean get() = allCvars != null

    fun isKnown(key: String): Boolean = key.lowercase() in (allCvars ?: emptySet())

    fun isMonitored(key: String): Boolean = key.lowercase() in (monitoredCvars ?: emptySet())

    fun gameDefault(key: String): String? = (defaultValues ?: emptyMap())[key.lowercase()]

    fun differsFromDefault(key: String, value: String): Boolean =
        gameDefault(key)?.let { it != value } ?: true

    /**
     * Comments out unknown cvars and default-matching monitored cvars (never deletes: the
     * commented line keeps the evidence). Unloaded database returns the text untouched rather
     * than guessing.
     */
    fun optimizeIniText(text: String): String {
        val all = allCvars ?: return text
        return optimizeWuWaIniText(text, all, monitoredCvars ?: emptySet(), defaultValues ?: emptyMap())
    }

    fun extractCvarValues(iniText: String): Map<String, String> = extractWuWaCvarValues(iniText)

    private companion object {
        const val ASSET_DIR = "wuwa_cvars"
    }
}

/**
 * The hygiene gate: the CVar-database pass is only effective with loaded tables
 * behind it. The screen disables the switch until the load lands; this object pins
 * the rule itself so a stray call can never silently optimize against nothing.
 */
object WuwaCvarDbGate {

    /** Whether the hygiene switch may be turned on at all. */
    fun canEnable(isLoaded: Boolean): Boolean = isLoaded

    /** Whether the generator actually runs the hygiene pass for this state. */
    fun effectiveDb(requested: Boolean, isLoaded: Boolean): Boolean = requested && isLoaded
}

/**
 * The hygiene pass itself, pure for testability: unknown cvars and redundant
 * (monitored + matches-default) lines are commented with their reason; structure lines,
 * `-CVars=` removal directives and non-cvar INI keys pass through byte-identical.
 */
fun optimizeWuWaIniText(
    text: String,
    allCvars: Set<String>,
    monitoredCvars: Set<String>,
    defaultValues: Map<String, String>
): String {
    // Shared with the generator's dedup pass: only these namespaces are console variables,
    // so section keys like `Paths=` can never be mistaken for unknown cvars.
    val prefixes = WuWaConfigGenerator.CVAR_PREFIXES
    val out = mutableListOf<String>()
    for (line in text.lines()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith(";") || trimmed.startsWith("#") ||
            trimmed.startsWith("//") || trimmed.startsWith("[")
        ) {
            out.add(line)
            continue
        }
        // A removal directive is never "unknown" — keep it verbatim.
        if (trimmed.startsWith("-CVars=", ignoreCase = true)) {
            out.add(line)
            continue
        }
        val cvarLine = trimmed.removePrefix("+CVars=").trim()
        if (cvarLine.isEmpty() || cvarLine.startsWith(";") || cvarLine.startsWith("#") ||
            cvarLine.startsWith("//") || cvarLine.startsWith("[")
        ) {
            out.add(line)
            continue
        }
        val eq = cvarLine.indexOf('=')
        if (eq <= 0) {
            out.add(line)
            continue
        }
        val key = cvarLine.substring(0, eq).trim()
        // UE INI treats ';' as a comment marker, never a value char: strip the inline comment
        // so the default comparison sees the real value.
        val value = cvarLine.substring(eq + 1).trim().substringBefore(';').trim()
        val k = key.lowercase()
        if (!prefixes.any { k.startsWith(it) }) {
            out.add(line)
            continue
        }
        val reason = when {
            k !in allCvars -> "unknown CVar"
            k in monitoredCvars && defaultValues[k] == value ->
                "redundant (matches default ${defaultValues[k]})"
            else -> null
        }
        if (reason != null) {
            out.add(";$line ; [CvarDB] $reason")
        } else {
            out.add(line)
        }
    }
    return out.joinToString("\n")
}

/** Maps every `key = value` cvar line in INI text (structure lines skipped, last wins). */
fun extractWuWaCvarValues(iniText: String): Map<String, String> {
    val result = mutableMapOf<String, String>()
    for (line in iniText.lines()) {
        val trimmed = line.trim()
        if (trimmed.startsWith(";") || trimmed.startsWith("#") || trimmed.startsWith("//") ||
            trimmed.startsWith("[") || trimmed.isEmpty()
        ) {
            continue
        }
        val cvarLine = trimmed.removePrefix("+CVars=").removePrefix("-CVars=").trim()
        if (cvarLine.isEmpty() || cvarLine.startsWith(";") || cvarLine.startsWith("#") ||
            cvarLine.startsWith("//") || cvarLine.startsWith("[")
        ) {
            continue
        }
        val eq = cvarLine.indexOf('=')
        if (eq <= 0) continue
        val key = cvarLine.substring(0, eq).trim()
        val value = cvarLine.substring(eq + 1).trim()
        if (key.isNotEmpty()) result[key] = value
    }
    return result
}
