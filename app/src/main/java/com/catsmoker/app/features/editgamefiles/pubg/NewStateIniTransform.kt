package com.catsmoker.app.features.editgamefiles.pubg

/**
 * PUBG: New State's `GameUserSettings.ini` transform (TODO M168), ported from the New State
 * branch of `reference/Magisk-Modules/Unlocker-p4/customize.sh` — its sed edits, minus the
 * module's concealment commands (`magiskhide`, `adb_enabled 0`, denylist edits — a rejected
 * pattern, TODO M170) and minus two image renames that were never about the game.
 *
 * What the reference edits, and what this port does with it:
 * - `InGameSetting=...` — replaced with the reference's tuple (every quality key `ULTRA`, the
 *   FPS key at `EXTREME90`, anti-aliasing on). The module's sed pattern (`InGameSetting=*`) only
 *   rewrites the prefix, so on a file that already carried a tuple it would leave the old one
 *   dangling behind the new one; this port replaces the whole line instead (deliberate
 *   divergence — same result on a file whose line was empty, a sane file on one whose was not).
 * - `FrameRateLimit=` — the reference maps 30 and 60 to `90.000000`. This port raises any value
 *   below 90 and leaves everything at or above 90 alone: identical results for the reference's
 *   two inputs, and it never lowers a cap the device already offers (no-cap rule).
 * - `AudioQualityLevel=` / `LastConfirmedAudioQualityLevel=` — the reference maps 1 and 2 to 0;
 *   this port zeroes any non-zero value (same intent, one rule).
 *
 * A file none of these keys appear in is refused (never write an unrecognized INI), and a file
 * already at every target is reported as unchanged. The caller owns the channel: the file lives
 * in the game's private data dir (`/data/data/com.pubg.newstate/...`), so root only.
 */
object NewStateIniTransform {

    const val PACKAGE = "com.pubg.newstate"
    const val CONFIG_DIR = "/data/data/com.pubg.newstate/files/UE4Game/Extreme/Extreme/Saved/Config/Android/"
    const val CONFIG_FILE = "GameUserSettings.ini"

    /** The reference's FPS target (`EXTREME90` tier and the FrameRateLimit seds). */
    const val TARGET_FPS = 90.0
    private const val TARGET_FPS_TEXT = "90.000000"

    /** The reference's whole replacement line, byte-for-byte from its sed. */
    const val REFERENCE_IN_GAME_LINE =
        "InGameSetting=(Brightness=135.000000,3e5fb0f167=HD,CameraFOV_FPP=95.000000," +
            "ee1cce4781=ULTRA,0e36c7ab25=ULTRA,52acd236cf=EXTREME90,30db48eba4=ULTRA," +
            "388652a957=OFF,CameraFOV_TPP=80.000000,d08a7d9304=ULTRA,AntiAliasingType=ON," +
            "d67592353d=ULTRA,728fe36b3e=ULTRA)"

    private const val KEY_IN_GAME = "InGameSetting="
    private const val KEY_FPS = "FrameRateLimit="
    private val AUDIO_KEYS = listOf("AudioQualityLevel=", "LastConfirmedAudioQualityLevel=")

    /**
     * @param bytes the transformed file, byte-identical to the input when nothing changed.
     * @param matched whether the file carried at least one key this transform knows — a file
     *   with none is not a New State settings file and the caller must not write it.
     * @param changed whether anything differed from the input.
     * @param changes one line per edit, for tests and diagnostics (not user-facing copy).
     */
    data class Result(
        val bytes: ByteArray,
        val matched: Boolean,
        val changed: Boolean,
        val changes: List<String>
    )

    fun apply(current: ByteArray): Result {
        val lines = current.toString(Charsets.UTF_8).split("\n")
        val changes = mutableListOf<String>()
        var matched = false

        val out = lines.map { raw ->
            val hasCr = raw.endsWith("\r")
            val line = if (hasCr) raw.dropLast(1) else raw
            var newLine = line

            when {
                line.startsWith(KEY_IN_GAME) -> {
                    matched = true
                    if (line != REFERENCE_IN_GAME_LINE) {
                        newLine = REFERENCE_IN_GAME_LINE
                        changes += "InGameSetting: replaced with the reference tuple"
                    }
                }
                line.startsWith(KEY_FPS) -> {
                    matched = true
                    val valueText = line.removePrefix(KEY_FPS).trim()
                    val value = valueText.toDoubleOrNull()
                    if (value != null && value < TARGET_FPS) {
                        newLine = KEY_FPS + TARGET_FPS_TEXT
                        changes += "FrameRateLimit: $valueText -> $TARGET_FPS_TEXT"
                    }
                }
                else -> {
                    val key = AUDIO_KEYS.firstOrNull { line.startsWith(it) }
                    if (key != null) {
                        matched = true
                        val valueText = line.removePrefix(key).trim()
                        if (valueText != "0") {
                            newLine = key + "0"
                            changes += "${key.trimEnd('=')}: $valueText -> 0"
                        }
                    }
                }
            }

            if (hasCr) newLine + "\r" else newLine
        }

        val bytes = out.joinToString("\n").toByteArray(Charsets.UTF_8)
        return Result(bytes, matched, !bytes.contentEquals(current), changes)
    }
}
