package com.catsmoker.app.shared.data.model

/**
 * Contract shared by the app UI and the Xposed module that runs inside other apps' processes.
 *
 * The single vocabulary on both sides is system-property names — exactly what
 * [com.catsmoker.app.shared.data.repository.SpoofRepository.renderConfig] emits for Magisk — so
 * `Build.MODEL` is derived from `ro.product.model` rather than duplicated under a second name.
 */
object LSPosedConfig {
    const val PREFS_NAME = "lsposed_prefs"
    const val KEY_ENABLED = "lsposed_enabled"
    const val KEY_TARGET_PACKAGES = "lsposed_target_packages"
    const val KEY_DEVICE_PROPS = "lsposed_device_props"
    const val KEY_MAGISK_PROPS = "magisk_system_props"
    const val KEY_GLOBAL_ENABLED = "catsmoker_lsposed_enabled"
    const val KEY_GLOBAL_TARGET_PACKAGES_B64 = "catsmoker_lsposed_target_packages_b64"
    const val KEY_GLOBAL_DEVICE_PROPS_B64 = "catsmoker_lsposed_device_props_b64"

    /**
     * Every assigned package's profile in one document, base64'd into Settings.Global.
     *
     * Settings.Global is readable from any process, which the config ContentProvider is not:
     * package-visibility filtering on API 30+ hides our authority from apps that never declared
     * a `<queries>` entry for it, and those are precisely the apps being spoofed.
     */
    const val KEY_GLOBAL_PROFILES_B64 = "catsmoker_lsposed_profiles_b64"

    /** Broadcast that tells already-running targets to re-read their profile. */
    const val ACTION_CONFIG_CHANGED = "com.catsmoker.app.action.CONFIG_CHANGED"

    /**
     * `elapsedRealtime` the module wrote when it last loaded into our own process.
     *
     * The app cannot query LSPosed for "is my module active", so the module reports
     * itself: [com.catsmoker.app.features.spoofdevice.root.LSPosedModule] records this
     * on every load of our own package (which the scope array includes for exactly this
     * purpose) and installs no spoof hooks there. Boot-relative clock, not wall time:
     * `elapsedRealtime` resets on every reboot, so a stored value greater than now can
     * only predate the last boot.
     */
    const val KEY_MODULE_HEARTBEAT_ELAPSED = "module_heartbeat_elapsed"

    /**
     * Whether a stored heartbeat proves the module loaded recently.
     *
     * Absent/non-positive never counts, greater-than-now means a reboot happened since the
     * write, and older than [MAX_HEARTBEAT_AGE_MS] means no own-process load for over a day —
     * which on-device evidence showed can happen while the card still claims Active (stale
     * module path after an app reinstall: nothing loads, the old heartbeat lingers). The
     * bound is safe because reading the status card runs inside our own freshly-started
     * process: a working module refreshes the heartbeat on that very start, so only a
     * genuinely unloaded module keeps reading stale. Reopen the app; still stale means the
     * module is not loading (disabled, out of scope, or a stale install).
     */
    fun isHeartbeatFresh(storedElapsed: Long, nowElapsed: Long): Boolean =
        storedElapsed in maxOf(1L, nowElapsed - MAX_HEARTBEAT_AGE_MS)..nowElapsed

    /** Heartbeats older than this prove nothing (see [isHeartbeatFresh]). */
    const val MAX_HEARTBEAT_AGE_MS = 24L * 60L * 60L * 1000L

    /** Key inside a rendered profile listing packages the user opted out of spoofing. */
    const val KEY_SAFE_MODE_PACKAGES = "safe_mode.packages"

    /**
     * Key inside a rendered profile listing packages that keep the real Android version only.
     *
     * The reference's `safe_mode.packages` IS its version-only list (`shouldBypassVersionSpoof`);
     * ours means full skip, so version-only mode got its own list and its own key (M113). A listed
     * package still receives the profile's model, fingerprint and everything else — only
     * [VERSION_PROPERTY_KEYS] are dropped before the hooks install, so every consumer
     * (`Build.VERSION` fields, `SystemProperties`/getprop, Java properties, the alias reads) falls
     * back to the device's real values with no per-hook code.
     */
    const val KEY_VERSION_BYPASS_PACKAGES = "version_bypass.packages"

    /**
     * The properties a version-only bypass keeps real.
     *
     * The reference's `isVersionProperty` set, verbatim: `release_or_codename` /
     * `release_or_preview_display` are the same value under the names newer Androids consult
     * (Android 12+), so they ride along even though our renderer emits only `release`.
     * Deliberately absent: `ro.build.version.codename` (a constant near-miss, never spoofed),
     * the fingerprint and build id/description (the model identity the mode exists to keep), and
     * every `preview_sdk` / `base_os` / `min_supported_target_sdk` neighbour.
     */
    val VERSION_PROPERTY_KEYS = setOf(
        "ro.build.version.release",
        "ro.build.version.release_or_codename",
        "ro.build.version.release_or_preview_display",
        "ro.build.version.sdk",
        "ro.build.version.security_patch",
        "ro.build.version.incremental"
    )

    /** True when [key] is one of the version properties a version-only bypass keeps real. */
    fun isVersionKey(key: String): Boolean = key in VERSION_PROPERTY_KEYS

    /** [props] minus its version properties; every other key passes through untouched. */
    fun stripVersionKeys(props: Map<String, String>): Map<String, String> =
        props.filterKeys { !isVersionKey(it) }

    /**
     * Resolves the two per-package user lists against one package's parsed profile.
     *
     * Safe mode (full skip) wins when both list the package; version-only strips exactly
     * [VERSION_PROPERTY_KEYS] and leaves everything else — the strip test pins all three shapes.
     * Returns [props] itself (same instance) when neither list matches, so a caller can tell an
     * untouched profile from a stripped one without a second lookup.
     */
    fun applyUserLists(props: Map<String, String>, packageName: String): Map<String, String> =
        when {
            packageName in parseTargetPackages(props[KEY_SAFE_MODE_PACKAGES]) -> emptyMap()
            packageName in parseTargetPackages(props[KEY_VERSION_BYPASS_PACKAGES]) ->
                stripVersionKeys(props)
            else -> props
        }

    /**
     * Packages the module will never spoof, whatever the config channels say.
     *
     * From the references' banking blacklists — `GameUnlocker-main/common/config.json`
     * (`cpu_spoof.blacklist`) and `COPG-JSON/module/COPG.json` (`:blocked` entries). In those
     * Zygisk modules the blacklist is the *primary* gate, because Zygisk loads into every
     * process on the device; here the LSPosed scope array is the primary gate and no banking
     * app is in it, so this list is a backstop for the one path around it: a user who widens
     * the scope by hand in the LSPosed manager and assigns a profile. A banking app reading a
     * spoofed identity is a fraud-detection hazard out of proportion to anything spoofing it
     * could gain, so this list overrides an explicit assignment — and the module logs that it
     * did, rather than spoofing nothing silently.
     *
     * The second half is privilege and identity infrastructure, added 2026-09-27 after the
     * lab proved why: spoofing the Magisk app crashed it (an SDK-37-targeting module host
     * running on Android 10), and a spoofed root manager, framework manager (LSPosed or
     * Vector — both entries stay listed), or privilege
     * broker risks breaking the very infrastructure Catsmoker stands on — including the
     * safety checks that read the true device through it. Our own package is here too: own
     * diagnostics must read real hardware (see the telemetry-honesty rule), so the module
     * must never rewrite what our own process sees, even if a user scopes us by hand.
     */
    val NEVER_SPOOF_PACKAGES = setOf(
        "com.bbl.mobilebanking",
        "com.sbi.YONO",
        "com.hdfcbank.payzapp",
        "com.csam.icici.bank.imobile",
        "com.topjohnwu.magisk",
        "me.weishu.kernelsu",
        "me.bmax.apatch",
        "org.lsposed.manager",
        "org.matrix.vector.manager",
        "moe.shizuku.privileged.api",
        "com.catsmoker.app"
    )

    /**
     * Key inside a rendered profile saying whether the profile's screen metrics should be applied.
     *
     * Named after the reference project's `ConfigManager.KEY_APPLY_SCREEN_METRICS` so both sides of
     * the config file speak the same vocabulary.
     */
    const val KEY_APPLY_SCREEN_METRICS = "device.apply_screen_metrics"

    /**
     * GL vendor string (`glGetString(GL_VENDOR)`) the profile should answer with, when set.
     *
     * Like `device.imei` this is one of our own profile keys, not a system property: there is no
     * `ro.` property behind it, so [SYSTEM_PROPERTY_PREFIXES] already keeps it out of the getprop
     * overlay and out of any channel that writes the real property store. Blank means "leave the
     * GPU strings alone" — a half-set GL identity (spoofed vendor, real renderer) is a stronger
     * fingerprint than neither, which is why the two keys are always rendered as a pair by the
     * editor.
     */
    const val KEY_GPU_VENDOR = "gpu.vendor"

    /** GL renderer string (`glGetString(GL_RENDERER)`), the partner of [KEY_GPU_VENDOR]. */
    const val KEY_GPU_RENDERER = "gpu.renderer"

    /**
     * Refresh rate in Hz the profile should answer `Display.getRefreshRate()` with, when set.
     *
     * Another of our own profile keys with no `ro.` property behind it — and there is no
     * system-property surface for refresh rate at all, so unlike the identity keys there is no
     * `getprop` half to disagree with: hooking the Display read is the entire reachable surface,
     * and the both-channels test is satisfied by construction. Zero (the editor's blank field)
     * means "leave the panel's real rate alone".
     *
     * The mechanism is the reference project's: `processHook.spoofRefreshRate` (read in full)
     * hooks every `Display.getRefreshRate` overload and forces the profile's rate, guarded on the
     * profile actually carrying one. What is deliberately not ported: its compiled per-package
     * DEVICE_MAP (assignments and frame-rate ladders own the package→profile mapping here), its
     * per-device `refreshrate` strings (reference model-table data, not preset candidates —
     * presets stay blank like their GPU strings), and HEAD's remote-JSON fetch design.
     */
    const val KEY_SCREEN_REFRESH_RATE = "screen.refresh_rate"

    /** Accepts the `1` / `true` spellings a rendered or hand-edited config can carry. */
    fun isFlagEnabled(value: String?): Boolean =
        value == "1" || value.equals("true", ignoreCase = true)

    /**
     * Whether the module hides emulator device files from the target (M116), `emulator.hide=1`.
     *
     * One of our own profile keys — nothing `ro.`-shaped backs it, so [SYSTEM_PROPERTY_PREFIXES]
     * already keeps it out of the property store and out of any `getprop` dump. When set, the
     * module reports the reference project's emulator artifact paths (and keyword paths under the
     * system subtrees) as absent to the target's Java `File` probes. Absent key = no file-layer
     * hooking, today's behavior. Deliberately not ported with it: the qemu/verifiedboot/selinux
     * property zeros — a real device has no `ro.kernel.qemu` to correct, and *adding* `=0` keys is
     * itself an emulator artifact.
     */
    const val KEY_EMULATOR_HIDE = "emulator.hide"

    /**
     * CPU core count a user-declared hardware fact answers (M117); 0/absent = real value.
     *
     * One of our own keys, read only by the module's hardware-fact hooks (see
     * [com.catsmoker.app.features.spoofdevice.root.SpoofHardwareFacts] for the Mb↔byte/kB
     * conversions both sides share). The values are facts about a device the user names — presets
     * never fill them.
     */
    const val KEY_HW_CPU_CORES = "hw.cpu_cores"

    /** RAM in MB a user-declared hardware fact answers (M117); 0/absent = real value. */
    const val KEY_HW_RAM_MB = "hw.ram_mb"

    /** Java heap class in MB (M117); 0/absent = real value. */
    const val KEY_HW_HEAP_MB = "hw.heap_mb"

    /** Large-heap class in MB (M117); 0/absent = real value. */
    const val KEY_HW_LARGE_HEAP_MB = "hw.large_heap_mb"

    /**
     * Per-app Android ID seed (M146), `device.id_seed`.
     *
     * When the profile's literal `ANDROID_ID` (or `ro.serialno`) is absent and this key is
     * present, the module derives a stable per-package value instead: `seed + ":" + package`
     * mixed through FNV-1a 64 → splitmix64 (the reference's algorithm, byte-for-byte — see
     * [com.catsmoker.app.features.spoofdevice.root.AndroidIdDerivation]). One literal ID shared
     * across apps is itself a signal on Android 8+, where differently-signed apps see different
     * IDs; the seed gives each assigned package its own. Blank = off; the literal fields always
     * win when set.
     */
    const val KEY_ANDROID_ID_SEED = "device.id_seed"

    /**
     * Prefixes that mark a rendered-profile entry as a real Android system property.
     *
     * A rendered profile is the vocabulary for every delivery channel, so it also carries keys only
     * this app's hooks understand — `device.imei`, `screen.width`, `safe_mode.packages`. Those have
     * no business reaching a channel that writes the real property store or a `getprop` dump, where
     * they are a giveaway rather than a disguise.
     *
     * This list lives here, not beside either consumer, because two channels must filter by the
     * same one. [com.catsmoker.app.features.spoofdevice.root.GetPropInterceptor] held it privately
     * and filtered correctly while the exported Magisk module wrote `renderConfig` output straight
     * into `system.prop` — so one identical profile was clean in-process and self-reporting once
     * flashed.
     */
    val SYSTEM_PROPERTY_PREFIXES = listOf(
        "ro.", "persist.", "gsm.", "net.", "dalvik.", "sys.", "vendor.", "debug."
    )

    /** True when [key] names a real system property rather than one of our own profile keys. */
    fun isSystemProperty(key: String): Boolean = SYSTEM_PROPERTY_PREFIXES.any(key::startsWith)

    /**
     * The real system properties of a rendered profile, in render order.
     *
     * Goes through [parseDeviceProps] so a channel publishing to the property store applies exactly
     * the comment, blank-line and malformed-line handling the hooks apply when reading the same
     * text back.
     */
    fun filterToSystemProperties(rendered: String?): Map<String, String> =
        parseDeviceProps(rendered).filterKeys(::isSystemProperty)

    fun parseTargetPackages(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return raw.split("\n", ",").asSequence().map { it.trim() }.filter { it.isNotEmpty() }.toCollection(LinkedHashSet())
    }

    fun parseDeviceProps(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            // A rendered profile's own comments carry no '=' and so were already dropped, but a
            // hand-edited config commenting a property out ("# ro.product.model=Pixel") would
            // otherwise be read back as a property literally named "# ro.product.model".
            if (trimmed.startsWith('#')) return@forEach
            val idx = trimmed.indexOf('=')
            if (idx <= 0 || idx == trimmed.length - 1) return@forEach
            val key = trimmed.substring(0, idx).trim()
            val value = trimmed.substring(idx + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) result[key] = value
        }
        return result
    }

    /**
     * Joins per-package rendered profiles into one document with `[package]` section headers.
     * Rendered profiles only ever contain `key=value` and `#` lines, so `[` cannot collide.
     */
    fun renderSections(configs: Map<String, String>): String {
        val sb = StringBuilder()
        for ((packageName, config) in configs) {
            if (packageName.isBlank() || config.isBlank()) continue
            sb.append('[').append(packageName).append("]\n")
            sb.append(config.trimEnd('\n')).append('\n')
        }
        return sb.toString()
    }

    /** Extracts one package's section from a [renderSections] document. */
    fun parseSection(raw: String?, packageName: String): String? {
        if (raw.isNullOrBlank() || packageName.isBlank()) return null
        val sb = StringBuilder()
        var inSection = false
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inSection = trimmed.substring(1, trimmed.length - 1) == packageName
                return@forEach
            }
            if (inSection) sb.append(line).append('\n')
        }
        return sb.toString().ifBlank { null }
    }

    /**
     * Extracts one package's section, falling back to `*` wildcard section headers when no
     * exact section exists. Exact wins first; longest (most specific) pattern wins ties; the
     * system guard refuses pattern matches for protected packages. The Settings.Global copy
     * the module reads without touching us carries pattern headers verbatim (see the sync in
     * `SpoofDeviceViewModel`), so both channels agree on families without a store migration.
     */
    fun parseSectionWildcard(raw: String?, packageName: String): String? {
        parseSection(raw, packageName)?.let { return it }
        if (raw.isNullOrBlank() || packageName.isBlank()) return null
        if (packageName in PROTECTED_PACKAGES) return null
        val names = LinkedHashSet<String>()
        raw.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                names.add(trimmed.substring(1, trimmed.length - 1))
            }
        }
        val winner = names.filter { it.contains('*') && matchesPatternKey(it, packageName) }
            .maxWithOrNull(compareBy({ it.length }, { it }))
        return winner?.let { parseSection(raw, it) }
    }

    /** Packages a wildcard may never claim, however broad the pattern. */
    internal val PROTECTED_PACKAGES = setOf(
        "android",
        "com.android.systemui"
    )

    /** Whether a `*` pattern key claims [packageName] (full match, `*` = any run). */
    internal fun matchesPatternKey(pattern: String, packageName: String): Boolean {
        val regex = Regex(pattern.split("*").joinToString(".*") { Regex.escape(it) })
        return regex.matches(packageName)
    }
}
