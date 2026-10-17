package com.catsmoker.app.features.gamingtools.engine

/**
 * Serialization of Gaming Mode's `RUN_IN_BACKGROUND` prior-mode record (M42).
 *
 * Activation stores one `<pkg>=<mode>` entry per package whose background op it set, with the
 * mode read before the write; deactivation restores them and drops only what it verified back.
 * Modes are the [AppOpsVerdict.KNOWN_MODES] words; a package name cannot contain `=`, and the
 * split is on the first one so a corrupt entry can never swallow another's mode.
 *
 * Kept out of `GamingOptimizationSnapshot` on purpose — that snapshot is captured before the
 * suspend sweep knows which packages it will touch, and the sibling `affected_pkgs` record is
 * a plain string set in the engine prefs for the same reason. This object is the one place
 * that knows the entries' shape. Pure so both halves are pinnable by a JVM unit test.
 */
object AppOpsRecord {

    fun encode(record: Map<String, String>): Set<String> =
        record.entries.map { (pkg, mode) -> "$pkg=$mode" }.toSet()

    /**
     * Entries a previous build or a corrupt write could have mangled are dropped rather than
     * guessed at: an entry without both halves, a blank package, or a mode outside
     * [AppOpsVerdict.KNOWN_MODES] yields no mapping — a record can only restore what it can
     * trust, and an untrusted entry must never become a written mode.
     */
    fun decode(entries: Set<String>): Map<String, String> = buildMap {
        for (entry in entries) {
            val separator = entry.indexOf('=')
            if (separator <= 0) continue
            val pkg = entry.substring(0, separator)
            val mode = entry.substring(separator + 1)
            if (pkg.isBlank() || mode !in AppOpsVerdict.KNOWN_MODES) continue
            put(pkg, mode)
        }
    }
}
