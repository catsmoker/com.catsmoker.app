package com.catsmoker.app.features.gamingtools.tools.interventions

import com.catsmoker.app.shared.data.model.SettingValue
import com.catsmoker.app.system.shell.ShellRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android 12+'s own per-game interventions, applied through the table the system itself consults.
 *
 * `device_config put game_overlay <pkg> mode=2,…fps=N:mode=3,…fps=N` is the same mechanism Android's
 * game frame-rate interventions documentation describes, and the one the reference project ships —
 * `referance/file-engineering/GenshinConfig-main/script/genshin.sh` puts it for Genshin Impact, and
 * its reverter `genshun.sh` takes it back out with `device_config delete`. Both halves are
 * reproduced here, format verbatim.
 *
 * This is *not* the same lever as the `cmd game set --mode performance --fps` Gaming Mode already
 * sends: that command sets which mode the game is in *right now*, while `game_overlay` is the
 * per-package table GameManager reads to decide what each mode means. Setting both the PERFORMANCE
 * (mode 2) and STANDARD (mode 3) rows means the raised cap holds whichever mode the system picks —
 * the reference script does the same, and one row without the other would silently lose the cap as
 * soon as the device decides it is not in performance mode.
 *
 * Two honest limits, both stated rather than hidden:
 *
 * - **The value can be taken away mid-session.** `device_config` entries are server-syncable flags,
 *   and a flag push from the OEM can overwrite whatever this app wrote. That is one more reason the
 *   read-back after writing exists, and why deactivation is an explicit `delete` rather than "leave
 *   it, it will be gone eventually".
 * - **Restore is shaped by what was there before.** A package that already carried an intervention
 *   gets its old value put back; one that carried none gets a `delete`. Writing an assumed "unset"
 *   over a real prior value would be the same mistake as restoring an assumed setting — it replaces
 *   something the user (or another tool) had set deliberately.
 *
 * Every write is confirmed by reading the value back, and a device that refuses the command reports
 * the refusal in its own words rather than counting the request as done.
 */
@Singleton
class GameInterventions @Inject constructor(
    private val shellRunner: ShellRunner
) {
    /**
     * What the device actually holds for one package.
     *
     * `device_config get` prints the literal string `null` when a flag is not set, which is a real
     * answer and must not be confused with a read that failed. [Unreadable] is kept apart from
     * [Unset] for the same reason [com.catsmoker.app.shared.data.model.SettingValue] keeps
     * `existed = false` apart from null: one is a reading, the other is the absence of one.
     */
    sealed interface OverlayValue {
        /** The table entry the device is holding, exactly as `device_config get` printed it. */
        data class Set(val value: String) : OverlayValue

        /** The device answered, and the package has no entry. */
        data object Unset : OverlayValue

        /** The device would not answer at all; [reason] is its own output. */
        data class Unreadable(val reason: String) : OverlayValue
    }

    /**
     * Whether an intervention landed.
     *
     * @param applied the read-back's verdict, never the command's exit code alone.
     * @param refusal the device's own words when it refused, or null when nothing was refused.
     */
    data class Outcome(val applied: Boolean, val refusal: String?)

    /** Reads the intervention a package currently has, or the reason it could not be read. */
    suspend fun readOverlay(pkg: String): OverlayValue = withContext(Dispatchers.IO) {
        val result = shellRunner.execSafeResult("device_config", "get", NAMESPACE, pkg)
        if (!result.isSuccess) {
            return@withContext OverlayValue.Unreadable(
                listOf(result.stdout, result.stderr).filter { it.isNotBlank() }.joinToString("\n")
                    .ifBlank { "device_config exited ${result.exitCode} and said nothing" }
            )
        }
        val raw = result.stdout.trim()
        // `null` and an empty line both mean "not set" — see SettingValue.fromCommandOutput, which
        // this mirrors rather than re-deriving, so the two channels can never disagree.
        val parsed = SettingValue.fromCommandOutput(raw)
        if (parsed.existed) OverlayValue.Set(parsed.value) else OverlayValue.Unset
    }

    /**
     * Writes the intervention for [pkg] at [maxFps] frames a second and confirms it stuck.
     *
     * The caller is expected to have captured the prior value first (Gaming Mode does, in its
     * snapshot, *before* anything is written) — this method only reports what happened, it does not
     * remember what was there.
     */
    suspend fun apply(pkg: String, maxFps: Int): Outcome = withContext(Dispatchers.IO) {
        val wanted = overlayValue(maxFps)
        val write = shellRunner.execSafeResult("device_config", "put", NAMESPACE, pkg, wanted)
        if (!write.isSuccess) {
            return@withContext Outcome(
                applied = false,
                refusal = listOf(write.stdout, write.stderr).filter { it.isNotBlank() }
                    .joinToString("\n").ifBlank { "device_config exited ${write.exitCode} and said nothing" }
            )
        }
        // The exit code only says `device_config` ran. What the device now holds is the fact.
        when (val readBack = readOverlay(pkg)) {
            is OverlayValue.Set -> Outcome(applied = readBack.value == wanted, refusal = null)
            is OverlayValue.Unset -> Outcome(applied = false, refusal = "device_config accepted the write but the flag did not stay set")
            is OverlayValue.Unreadable -> Outcome(applied = false, refusal = readBack.reason)
        }
    }

    /**
     * Puts the table back the way it was before Gaming Mode touched it.
     *
     * [previous] comes from the activation snapshot, so it is either the entry the device really
     * held or `existed = false` for "had none" — the same contract every other snapshot field
     * carries. A `null` previous never reaches here; Gaming Mode leaves the flag alone when it
     * could not read it, because touching what was not measured is how an assumed value ends up
     * written over a deliberate one.
     *
     * @return whether the table now holds what it held before, per a read-back.
     */
    suspend fun restore(pkg: String, previous: SettingValue): Boolean = withContext(Dispatchers.IO) {
        if (previous.existed) {
            val put = shellRunner.execSafeResult(
                "device_config", "put", NAMESPACE, pkg, previous.value
            )
            put.isSuccess && (readOverlay(pkg) as? OverlayValue.Set)?.value == previous.value
        } else {
            // The package had no entry, so removing ours is the restore — this is `genshun.sh`'s
            // own revert step.
            val delete = shellRunner.execSafeResult("device_config", "delete", NAMESPACE, pkg)
            delete.isSuccess && readOverlay(pkg) == OverlayValue.Unset
        }
    }

    companion object {
        /** The `device_config` namespace Android's game interventions live in. */
        private const val NAMESPACE = "game_overlay"

        /**
         * The intervention entry for [maxFps], in the reference script's exact format.
         *
         * One PERFORMANCE (mode 2) and one STANDARD (mode 3) row, joined by `:` — the format
         * `game_overlay` parses — with `downscaleFactor=false` so the intervention cannot silently
         * render the game at a lower resolution to buy the frame rate. Both rows are needed: a
         * device that reports itself out of performance mode consults the STANDARD row, and only
         * one row would mean the cap holds exactly until then.
         */
        fun overlayValue(maxFps: Int): String =
            "mode=2,opengles=0,downscaleFactor=false,fps=$maxFps" +
                ":mode=3,opengles=0,downscaleFactor=false,fps=$maxFps"
    }
}
