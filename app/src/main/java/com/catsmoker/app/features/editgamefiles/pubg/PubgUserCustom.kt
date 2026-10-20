package com.catsmoker.app.features.editgamefiles.pubg

/**
 * The `UserCustom.ini` write shape the closed-source PUBG GFX tools use (TODO M190/M191).
 *
 * The game's file carries two sections: `[UserCustom DeviceProfile]` (the active profile, all
 * values in the native `+CVars=<hex>` encoding) and `[BackUp DeviceProfile]` (the profile the
 * game had before the user's custom one). The eu tool's writer reads the current file, keeps
 * everything from the `[BackUp DeviceProfile]` marker on, and writes `template + "\n" + tail` —
 * which is exactly [mergePreservingBackup].
 *
 * The shipped template (`assets/PUBG/UserCustom/DeviceProfile.ini`) is the eu tool's own string
 * constant, byte-identical to 29/29 lines of the pinned eu fixture and 28 lines of the live
 * 4.6.0 repull (`app/src/test/resources/pubg_usercustom_*.ini`). What the hex bodies decode to
 * is still unverified (TODO M190) — so the previous file always goes to `ConfigBackupStore`
 * before a write, and the game's own backup section is never touched.
 */
object PubgUserCustom {

    /** The marker the game writes above its pre-custom profile; everything from here on is kept. */
    const val BACKUP_SECTION = "[BackUp DeviceProfile]"

    /**
     * Bytes to write: [template] verbatim, a blank line, then everything the device's current
     * file holds from [BACKUP_SECTION] on. A file without the marker — or no file at all — gets
     * the template alone (a fresh install has no backup section to keep).
     */
    fun mergePreservingBackup(current: ByteArray?, template: ByteArray): ByteArray {
        val templateText = String(template, Charsets.UTF_8)
        val normalized = if (templateText.endsWith("\n")) templateText else templateText + "\n"
        val currentText = current?.toString(Charsets.UTF_8)
            ?: return normalized.toByteArray(Charsets.UTF_8)
        val marker = currentText.indexOf(BACKUP_SECTION)
        if (marker < 0) return normalized.toByteArray(Charsets.UTF_8)
        return (normalized + "\n" + currentText.substring(marker)).toByteArray(Charsets.UTF_8)
    }
}
