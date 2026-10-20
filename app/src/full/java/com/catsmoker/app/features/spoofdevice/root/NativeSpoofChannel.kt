package com.catsmoker.app.features.spoofdevice.root

import android.util.Log
import com.catsmoker.app.shared.data.model.LSPosedConfig
import com.catsmoker.app.system.shell.NativeBridge
import de.robv.android.xposed.XposedBridge

/**
 * The native half of the spoof channel: properties read through NDK/bionic
 * (`__system_property_get`, `__system_property_read_callback`, `__system_property_read`) and
 * the GL strings read through the native `glGetString`.
 *
 * Why it exists: the Java hooks cover Java readers and [GetPropInterceptor] covers spawned
 * `getprop` binaries, but neither reaches a reader inside the target's own native code — a game
 * SDK, an anti-cheat library, Unity's native renderer. Those read the *device* while the same
 * process's Java readers see the profile, and that disagreement is a stronger signal than
 * either value alone (the same both-channels test [GetPropInterceptor] exists for).
 *
 * How it reaches the process: through the library every other surface already ships
 * (`libcatsmoker_bridge.so`). The host framework's native module API — declared in the module's
 * full-only `assets/native_init` — calls the library's `native_init` entry point when it loads
 * and hands it that framework's inline-hook engine. The hooks, the fixed-size tables and the
 * state mask live in `app/src/main/jni/catsmoker_spoof.c` (full flavor only, like this class).
 *
 * What it deliberately does not do:
 * - No writes anywhere: the hooks answer reads and pass every unhandled call to the real
 *   function. A process that never receives a profile installs no hooks at all.
 * - No fabricated answers: a key the profile does not carry falls through to the device's own
 *   value, and a long value is truncated exactly the way bionic truncates (91 characters).
 * - EGL strings (`eglQueryString`) are platform strings and stay untouched.
 *
 * Limits to carry honestly: a framework without the native API never calls `native_init`, so the
 * state mask reports no hooks and the Java half spoofs alone; the native `glGetString` hook is
 * installed when the GL library is loaded, so a process that already had GL mapped before the
 * module attached keeps the device's strings (also visible in the mask).
 */
internal object NativeSpoofChannel {

    /** The framework's native API was present and handed us a usable hook engine. */
    const val STATE_NATIVE_INIT = 1

    /** `__system_property_get` is hooked. */
    const val STATE_PROP_GET = 2

    /** `__system_property_read_callback` is hooked. */
    const val STATE_PROP_READ_CALLBACK = 4

    /** `__system_property_read` is hooked. */
    const val STATE_PROP_READ = 8

    /** Native `glGetString` in `libGLESv2.so` is hooked. */
    const val STATE_GL_GLES2 = 16

    /** A profile reached this process: hooks are installed and the tables carry it. */
    const val STATE_CONFIGURED = 32

    /** Native `glGetString` in `libGLESv1_CM.so` is hooked. */
    const val STATE_GL_GLES1 = 64

    /**
     * Pushes the resolved profile into this process's native half.
     *
     * Empty [props] clears the native table and the GL strings, which is how an un-assigned
     * profile takes effect without unloading anything: the hooks stay installed and answer
     * pass-throughs, exactly as the Java hooks read an empty profile.
     *
     * Called once per config change — from `Application.attach` and from the config broadcast.
     * Never called in CatSmoker's own process: spoof code must never run there.
     */
    fun install(props: Map<String, String>) {
        if (!NativeBridge.preloadFromClassLoader()) {
            // No bridge in this process (or no loader that can resolve it). The Java half still
            // spoofs, so this is a capability note, not a failure.
            log("bridge library unavailable — native property/GL hooks stay off")
            return
        }
        val table = PropAliases.expandAliases(props.filterKeys(LSPosedConfig::isSystemProperty))
        val applied = nativeSetSpoofProps(renderProps(table))
        nativeSetAliasRules(renderRules())
        nativeSetGpuStrings(
            props[LSPosedConfig.KEY_GPU_VENDOR],
            props[LSPosedConfig.KEY_GPU_RENDERER]
        )
        val dropped = if (applied < table.size) " (dropped ${table.size - applied})" else ""
        log("state=${describe(nativeChannelState())} props=$applied/${table.size}$dropped")

        // Evidence from inside this process: what a native reader gets for the key every
        // rendered profile answers. Both sides are logged — the check is the comparison,
        // never a claim that the hook worked.
        if (applied > 0 && table.containsKey(MODEL_KEY)) {
            log("probe $MODEL_KEY src=${table[MODEL_KEY]} seen=${nativeProbeProperty(MODEL_KEY)}")
        }
    }

    /** `key=value` lines, one per entry — the grammar `catsmoker_spoof.c` splits on. */
    internal fun renderProps(table: Map<String, String>): String =
        table.entries.joinToString("\n") { "${it.key}=${it.value}" }

    /** `prefix|suffix=canonical` lines — the alias rules the native lookup applies. */
    internal fun renderRules(): String =
        PropAliases.SUFFIX.joinToString("\n") { "${it.prefix}|${it.suffix}=${it.canonical}" }

    /** Human-readable [state] for logs and field reports; bits this build does not know are ignored. */
    internal fun describe(state: Int): String {
        val names = mutableListOf<String>()
        if (state and STATE_NATIVE_INIT != 0) names.add("native_api")
        if (state and STATE_PROP_GET != 0) names.add("prop_get")
        if (state and STATE_PROP_READ_CALLBACK != 0) names.add("prop_read_callback")
        if (state and STATE_PROP_READ != 0) names.add("prop_read")
        if (state and STATE_GL_GLES2 != 0) names.add("gl_gles2")
        if (state and STATE_GL_GLES1 != 0) names.add("gl_gles1")
        if (state and STATE_CONFIGURED != 0) names.add("configured")
        return if (names.isEmpty()) "none" else names.joinToString("+")
    }

    /** Replaces the native property table; returns how many entries the native side stored. */
    private external fun nativeSetSpoofProps(blob: String): Int

    /** Replaces the native alias rules; returns how many rules the native side stored. */
    private external fun nativeSetAliasRules(blob: String): Int

    /** Sets the spoofed GL strings; null leaves that string to the device. */
    private external fun nativeSetGpuStrings(vendor: String?, renderer: String?)

    /** The state bitmask above, read from the library itself. */
    private external fun nativeChannelState(): Int

    /**
     * What a native property read returns *in this process* for [key], or null when the process
     * has no such property. Goes through the same hooked entry point a target's own native code
     * would, which makes it evidence rather than a self-report.
     */
    private external fun nativeProbeProperty(key: String): String?

    /** The identity key a rendered profile normally answers; absent ⇒ nothing to probe. */
    private const val MODEL_KEY = "ro.product.model"

    private const val TAG = "CatsmokerNative"

    /** Logcat for the app-side log; LSPosed log too, where a field report can reach it. */
    private fun log(message: String) {
        Log.i(TAG, message)
        runCatching { XposedBridge.log("$TAG: $message") }
    }
}
