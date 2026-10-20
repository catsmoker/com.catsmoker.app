package com.catsmoker.app.features.gamingtools.engine

import android.content.Context
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Display
import com.catsmoker.app.shared.util.isVivoOrIqoo
import com.catsmoker.app.system.shell.ShellRunner
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Single source of truth for refresh-rate control (consolidation F3).
 *
 * Gaming Mode's refresh lock and Developer Options' "Always fastest" switch both
 * wrote `system:min_refresh_rate` independently — different formats (int vs float),
 * different stashes, different revert paths — so enabling both overwrote each other
 * and a snapshot could capture the forced value as the "original".
 *
 * Both now delegate here. The objective is always the panel's measured maximum:
 * this controller never lowers the ceiling; it only pins the minimum to the peak
 * (which forces the panel up) or restores a previous value.
 */
@Singleton
class RefreshRateController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shellRunner: ShellRunner,
    private val refreshRates: DisplayRefreshRateProvider
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The panel's measured peak in Hz — the only target this controller ever writes. */
    fun peakHz(): Float = refreshRates.getMaxHardwareRefreshRate()

    fun peakHzInt(): Int = peakHz().toInt()

    /** Current `min_refresh_rate` via the provider (no privilege needed), or null when unset. */
    fun readMinHz(): Float? = try {
        Settings.System.getString(context.contentResolver, KEY_MIN_REFRESH_RATE)?.toFloatOrNull()
    } catch (_: Exception) {
        null
    }

    /** Whether the panel is currently pinned to its peak (within storage tolerance). */
    fun isForcePeakActive(): Boolean {
        val peak = peakHz()
        if (peak <= SINGLE_SPEED_HZ) return false
        return isAtPeak(readMinHz() ?: NO_CONFIG, peak)
    }

    /** Whether this device can write system settings (privilege or WRITE_SETTINGS grant). */
    fun canWrite(): Boolean =
        shellRunner.hasPrivilege() || Settings.System.canWrite(context)

    /**
     * Reads the OEM refresh keys this device happens to carry — **read-only**.
     *
     * The key vocabulary comes from JordanTweaks (`OemRefreshKeys`); the writes that
     * script performs are counter-examples (hardcoded 120s, a `null`-string
     * uninstaller). This probe exists so a future session starts from the device's
     * own answer: which vendor key is real here, and what rate it already holds.
     * Nothing in this method writes, and no caller uses the result as a write target.
     */
    suspend fun probeOemKeys(): List<OemRefreshKeys.Reading> = withContext(Dispatchers.IO) {
        val values = OemRefreshKeys.candidates.associateWith { key ->
            readSettingRaw(key.namespace, key.key)
        }
        OemRefreshKeys.readings(values)
    }

    /** One settings row by namespace, preferring the in-process provider over the shell. */
    private fun readSettingRaw(namespace: String, key: String): String? {
        try {
            val direct = when (namespace) {
                "system" -> Settings.System.getString(context.contentResolver, key)
                "secure" -> Settings.Secure.getString(context.contentResolver, key)
                else -> null
            }
            if (!direct.isNullOrBlank()) return direct
        } catch (_: Exception) { }
        val result = runCatching {
            kotlinx.coroutines.runBlocking {
                shellRunner.execSafeResult("settings", "get", namespace, key)
            }
        }.getOrNull() ?: return null
        if (!result.isSuccess) return null
        val text = result.stdout.trim()
        return text.ifEmpty { null }
    }

    /**
     * Pins peak + min to the panel maximum (the Gaming Mode lock).
     *
     * @return which keys verified after write-back.
     */
    suspend fun lockToPeak(): LockResult {
        val maxHz = peakHzInt()
        val peakOk = putVerified(KEY_PEAK_REFRESH_RATE, maxHz.toString())
        val minOk = putVerified(KEY_MIN_REFRESH_RATE, maxHz.toString())
        return LockResult(peakOk = peakOk, minOk = minOk, lockedHz = maxHz)
    }

    /**
     * Raises the minimum to the peak (the "Always fastest" switch).
     *
     * Stashes the previous value first (unless already forced, which would record
     * our own value), mirroring the platform controller's own semantics.
     */
    suspend fun setForcePeak(enabled: Boolean): Boolean {
        val peak = peakHz()
        if (enabled) {
            if (isForcePeakActive()) return true
            val previous = readMinHz()
            prefs.edit().putString(PREF_PREVIOUS_MIN_HZ, previous?.toString() ?: "").apply()
            return writeFloat(KEY_MIN_REFRESH_RATE, peak)
        } else {
            val stashed = prefs.getString(PREF_PREVIOUS_MIN_HZ, null)
                ?: legacyStash()
            val previous = stashed?.toFloatOrNull()
            val ok = when {
                previous != null -> writeFloat(KEY_MIN_REFRESH_RATE, previous)
                stashed != null -> deleteMin()
                else -> writeFloat(KEY_MIN_REFRESH_RATE, NO_CONFIG)
            }
            prefs.edit().remove(PREF_PREVIOUS_MIN_HZ).apply()
            return ok
        }
    }

    data class LockResult(val peakOk: Boolean, val minOk: Boolean, val lockedHz: Int) {
        val locked: Boolean get() = peakOk || minOk
    }

    /**
     * Outcome of the vendor half of the lock.
     *
     * @param applicable true only on vivo/iQOO — every other device is "not applicable",
     *   never a failure (the same convention [GamingEngine.applyVendorGpuPerformance] uses
     *   with its null).
     * @param ok whether the panel now sits on an enumerated mode at the measured peak. A
     *   panel with no mode at that rate answers false and nothing was written.
     */
    data class VendorDisplayLock(val applicable: Boolean, val ok: Boolean)

    /**
     * Pins vivo/iQOO's own display-mode switch to the panel's measured peak
     * ([VivoDisplayMode] carries the lineage and the deliberate divergences from
     * `reference/gamingtools/booster`'s hardcoded mode id).
     *
     * Two keys, both read back: `secure user_preferred_display_mode_id` (the enumerated
     * mode id at the peak rate) and `global vivo_screen_refresh_rate_mode`
     * ([VivoDisplayMode.REFRESH_RATE_MODE_VALUE]), written beside the standard
     * `min_refresh_rate`/`peak_refresh_rate` pair because OriginOS honours its own switch
     * over them on some builds. The pre-activation snapshot records both keys, so the
     * deactivation path puts the device back exactly as it was.
     *
     * A non-vivo device returns `applicable = false` without touching the display service;
     * a panel with no mode at the measured peak, or one already on it, returns
     * `applicable = true` with the honest ok/not-ok and no write.
     */
    suspend fun lockVendorDisplayMode(): VendorDisplayLock = withContext(Dispatchers.IO) {
        if (!isVivoOrIqoo()) return@withContext VendorDisplayLock(applicable = false, ok = false)

        val display = runCatching {
            (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull() ?: return@withContext VendorDisplayLock(applicable = true, ok = false)

        val modes = runCatching { display.supportedModes }.getOrNull()
            ?.map { VivoDisplayMode.Mode(it.modeId, it.physicalWidth, it.physicalHeight, it.refreshRate) }
            .orEmpty()
        val peak = peakHz()
        val currentId = runCatching { display.mode?.modeId }.getOrNull()
        val chosen = VivoDisplayMode.bestModeId(modes, peak, currentId)
        if (chosen == null) {
            // Nothing to write. Either the panel already sits at the peak (success: the
            // state this lock exists for), or it has no mode at that measured rate — in
            // which case no id is invented and the answer is a refusal, not a pass.
            val current = modes.firstOrNull { it.id == currentId }
            val atPeak = current != null && abs(current.refreshRateHz - peak) <= HZ_TOLERANCE
            return@withContext VendorDisplayLock(applicable = true, ok = atPeak)
        }

        val idOk = putVerifiedIn(
            "secure", VivoDisplayMode.KEY_PREFERRED_DISPLAY_MODE_ID, chosen.toString()
        )
        val modeOk = putVerifiedIn(
            "global", VivoDisplayMode.KEY_SCREEN_REFRESH_RATE_MODE, VivoDisplayMode.REFRESH_RATE_MODE_VALUE
        )
        VendorDisplayLock(applicable = true, ok = idOk && modeOk)
    }

    /**
     * Writes one settings row through the shell and confirms it by read-back. Numbers are
     * compared numerically because some providers normalise `120` to `120.0`; the vendor's
     * mode-id key is a plain integer string.
     */
    private suspend fun putVerifiedIn(namespace: String, key: String, value: String): Boolean {
        shellRunner.execSafeResult("settings", "put", namespace, key, value)
        val readBack = readSettingValue(namespace, key) ?: return false
        val actual = readBack.toFloatOrNull()
        val wanted = value.toFloatOrNull()
        return if (actual != null && wanted != null) actual == wanted else readBack == value
    }

    /** One settings row by namespace, provider first, then the shell. Null when unreadable. */
    private suspend fun readSettingValue(namespace: String, key: String): String? {
        try {
            val direct = when (namespace) {
                "system" -> Settings.System.getString(context.contentResolver, key)
                "secure" -> Settings.Secure.getString(context.contentResolver, key)
                "global" -> Settings.Global.getString(context.contentResolver, key)
                else -> null
            }
            if (!direct.isNullOrBlank()) return direct
        } catch (_: Exception) { }
        val result = runCatching {
            shellRunner.execSafeResult("settings", "get", namespace, key)
        }.getOrNull() ?: return null
        if (!result.isSuccess) return null
        return result.stdout.trim().ifEmpty { null }
    }

    // ------------------------------------------------------------------ plumbing

    private suspend fun putVerified(key: String, value: String): Boolean {
        shellRunner.execSafeResult("settings", "put", "system", key, value)
        if (isVerified(key, value)) return true
        // The shell path missed (typically no privilege on a legacy device): with the
        // WRITE_SETTINGS grant the app writes the setting directly instead of through the
        // shell — the reference's `<=29 putInt` fallback, modernized with no version gate,
        // since the grant works on every version this app supports. Without the grant there
        // is nothing to fall back to.
        if (!Settings.System.canWrite(context)) return false
        runCatching { Settings.System.putString(context.contentResolver, key, value) }
        return isVerified(key, value)
    }

    private fun isVerified(key: String, value: String): Boolean {
        val readBack = readRaw(key) ?: return false
        val actual = readBack.toFloatOrNull()
        val wanted = value.toFloatOrNull()
        return if (actual != null && wanted != null) actual == wanted else readBack == value
    }

    private suspend fun writeFloat(key: String, value: Float): Boolean = withContext(Dispatchers.IO) {
        val formatted = String.format(Locale.US, "%.2f", value)
        if (shellRunner.hasPrivilege()) {
            shellRunner.execSafeResult("settings", "put", "system", key, formatted)
        }
        val landed = readMinHz()?.let { abs(it - value) < HZ_TOLERANCE } == true
        if (!landed && Settings.System.canWrite(context)) {
            runCatching { Settings.System.putFloat(context.contentResolver, key, value) }
        }
        readMinHz()?.let { abs(it - value) < HZ_TOLERANCE } == true
    }

    private suspend fun deleteMin(): Boolean = withContext(Dispatchers.IO) {
        shellRunner.execSafeResult("settings", "delete", "system", KEY_MIN_REFRESH_RATE)
        // Deleted reads back as unset (null) — the platform's own "no minimum".
        readRaw(KEY_MIN_REFRESH_RATE) == null
    }

    private fun readRaw(key: String): String? {
        try {
            Settings.System.getString(context.contentResolver, key)?.let { return it }
        } catch (_: Exception) { }
        val result = runCatching {
            // Blocking read inside IO callers only; kept synchronous to preserve
            // the verified-write contract without restructuring callers.
            kotlinx.coroutines.runBlocking {
                shellRunner.execSafeResult("settings", "get", "system", key)
            }
        }.getOrNull() ?: return null
        if (!result.isSuccess) return null
        val text = result.stdout.trim()
        return text.ifEmpty { null }
    }

    /**
     * Picks up a stash written by GameDeveloperOptions before the migration, so
     * users who forced peak on the old build restore their own value, not 0.
     */
    private fun legacyStash(): String? = try {
        val legacy = context.getSharedPreferences(
            "game_developer_options", Context.MODE_PRIVATE
        ).getString("previous_min_refresh_rate", null)
        if (legacy != null) {
            context.getSharedPreferences("game_developer_options", Context.MODE_PRIVATE)
                .edit().remove("previous_min_refresh_rate").apply()
        }
        legacy
    } catch (_: Exception) {
        null
    }

    companion object {
        const val KEY_MIN_REFRESH_RATE = "min_refresh_rate"
        const val KEY_PEAK_REFRESH_RATE = "peak_refresh_rate"

        /** Below this there is nothing to force; matches the platform threshold. */
        const val SINGLE_SPEED_HZ = 60f

        /** The platform's "no minimum" value. */
        const val NO_CONFIG = 0f

        /** Rates differing by less than this are the same rate, stored differently. */
        const val HZ_TOLERANCE = 0.5f

        private const val PREFS_NAME = "refresh_rate_controller"
        private const val PREF_PREVIOUS_MIN_HZ = "previous_min_hz"

        /** Pure predicate — the unit-testable seam. */
        fun isAtPeak(currentHz: Float, peakHz: Float): Boolean =
            currentHz >= peakHz - HZ_TOLERANCE
    }
}
