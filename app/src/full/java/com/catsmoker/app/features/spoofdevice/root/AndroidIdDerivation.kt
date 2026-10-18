package com.catsmoker.app.features.spoofdevice.root

import com.catsmoker.app.shared.data.model.LSPosedConfig

/**
 * Per-app Android ID derivation (M146): `seed + ":" + subject` mixed through
 * FNV-1a 64 → splitmix64 and printed as 16 lower-case hex.
 *
 * Ported byte-for-byte from the reference's `deriveAndroidId`
 * (`reference/spoofdevice/COPG-JSON/webroot/js/copg-data.js`, read in full): FNV-1a 64 over the
 * low byte of every UTF-16 unit (`charCodeAt(i) & 0xff` — identical to the native C++ for the
 * ASCII domain of hex seeds and package names, and the JS shape is what [AndroidIdDerivationTest]
 * pins), then the splitmix64 finalizer. 64-bit wrap is the reference's `& MASK`; Kotlin `Long`
 * arithmetic wraps to the same bit pattern, so every step below is just `xor`, `ushr` and `*`.
 *
 * Two deliberate divergences from the reference:
 * - No `clean()`: the reference strips everything from the first `:` off its tagged package
 *   syntax before mixing. Our subjects are plain package names plus the documented `serial:`
 *   domain prefix below, and that colon-strip would eat the prefix.
 * - The reference derives the Android ID only; this module derives the serial from the same seed
 *   too (see [withDerivedIds]). The literal fields always win when set.
 *
 * Pure Kotlin on purpose: the module runs it in-process (no Context, no Hilt) and the JVM tests
 * run it against vectors computed from the reference JavaScript itself.
 */
object AndroidIdDerivation {

    /** Serial subjects carry this prefix, so one seed yields two unrelated values per package. */
    fun serialSubject(packageName: String): String = "serial:$packageName"

    /**
     * The reference mix: [seed] plus `":"` plus [subject] → 16 lower-case hex.
     *
     * An empty seed returns `""` — the reference's `if (!seed) return ''` guard; callers treat a
     * blank seed as "off" before getting here ([withDerivedIds]). A blank [subject] would still
     * mix to a stable value; callers only ever pass non-blank subjects.
     */
    fun derive(seed: String, subject: String): String {
        if (seed.isEmpty()) return ""
        var h = FNV_OFFSET_BASIS
        val mixed = seed + ":" + subject
        for (char in mixed) {
            h = h xor (char.code and 0xFF).toLong()
            h *= FNV_PRIME
        }
        h += SPLITMIX_ADD
        h = (h xor (h ushr 30)) * SPLITMIX_MUL1
        h = (h xor (h ushr 27)) * SPLITMIX_MUL2
        h = h xor (h ushr 31)
        return java.lang.Long.toHexString(h).padStart(16, '0')
    }

    /**
     * The module's read-path completion: when a profile carries a seed but the literal value is
     * absent, add the derived `ANDROID_ID` / `ro.serialno` entries to the parsed map.
     *
     * This is the single injection point precisely so every consumer — the `Settings.Secure`
     * hook, `Build.getSerial`, `SystemProperties` reads, the `getprop` dump merge — reads one map
     * and cannot disagree. Literal keys win: a present, non-blank entry is never overwritten.
     * Returns the same instance when nothing was added (the module logs on identity change), and
     * never adds anything for a blank seed, a blank package name, or safe mode (the caller runs
     * this only on a non-empty map).
     */
    fun withDerivedIds(props: Map<String, String>, packageName: String): Map<String, String> {
        val seed = props[LSPosedConfig.KEY_ANDROID_ID_SEED]?.takeIf { it.isNotBlank() } ?: return props
        if (packageName.isBlank()) return props

        var out = props
        if (out[KEY_ANDROID_ID].isNullOrBlank()) {
            out = LinkedHashMap(out).apply { put(KEY_ANDROID_ID, derive(seed, packageName)) }
        }
        if (out[KEY_SERIALNO].isNullOrBlank()) {
            out = LinkedHashMap(out).apply { put(KEY_SERIALNO, derive(seed, serialSubject(packageName))) }
        }
        return out
    }

    /** Literal Android ID key, exactly as the renderer emits it (`SpoofRepository.renderConfig`). */
    const val KEY_ANDROID_ID = "ANDROID_ID"

    /** Literal serial key; the same surface `Build.getSerial` and `SystemProperties` answer from. */
    const val KEY_SERIALNO = "ro.serialno"

    /** FNV-1a 64 offset basis and prime; hex kept in the comment because it exceeds Long range. */
    private const val FNV_OFFSET_BASIS = -3750763034362895579L // 0xCBF29CE484222325
    private const val FNV_PRIME = 1099511628211L // 0x00000100000001B3

    /** splitmix64 constants, as signed Long bit patterns (they are ulong literals in the JS). */
    private const val SPLITMIX_ADD = -7046029254386353131L // 0x9E3779B97F4A7C15
    private const val SPLITMIX_MUL1 = -4658895280553007687L // 0xBF58476D1CE4E5B9
    private const val SPLITMIX_MUL2 = -7723592293110705685L // 0x94D049BB133111EB
}
