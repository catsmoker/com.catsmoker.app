package com.catsmoker.app.system.shell

/**
 * The in-app native bridge: a JNI library (`libcatsmoker_bridge.so`) built from
 * `app/src/main/jni/` and packaged inside this APK.
 *
 * The library has two roles:
 * - build identity ([version]) and load state ([isAvailable]) for Diagnostics;
 * - the M152 Unity frame-rate channel: [isIl2cppReady], [unityTargetFrameRate] and
 *   [unitySetTargetFrameRate] locate `il2cpp_resolve_icall` in the *current* process's
 *   mapped `libil2cpp.so` and read/invoke Unity's `Application` icalls. The only
 *   behavioral caller is `UnityFrameRateDelivery`, which is raise-only and runs only in
 *   an assigned target process (see that class).
 *
 * In a target process the same library also carries the native spoof hooks
 * (`catsmoker_spoof.c`, full flavor only — see `NativeSpoofChannel`): the framework's native
 * module API calls its `native_init` when the library is loaded.
 *
 * Load routes: [preloadFromClassLoader] is the one a module target tries first — the module
 * loader resolves the packaged zip path, which is also the load the framework's native API
 * watches for. [preload] (an extracted absolute path) is the fallback for a loader that cannot
 * answer `findLibrary`, and it maps a second copy the framework never sees.
 *
 * Honesty rules the API lives by:
 * - [isAvailable] is a load attempt, not a capability probe — true means "the `.so` for
 *   this ABI packaged and loaded", nothing more.
 * - [version] is the build identity the library reports about itself, or null when the
 *   library is absent (JVM unit-test hosts) or the call throws. Never a guessed string.
 * - [isIl2cppReady] is a `/proc/self/maps` fact, never "this is a Unity game".
 * - The Unity get/set calls return negative stage codes instead of guessed rates; a
 *   successful setter result means "invoked", not "the frame rate changed".
 */
object NativeBridge {

    @Volatile
    private var loaded = false

    @Volatile
    private var loadAttempted = false

    /**
     * True when the packaged `.so` for this ABI loaded. A load fact, not a capability claim.
     */
    fun isAvailable(): Boolean = ensureLoaded()

    /**
     * Loads the bridge the module route uses: `System.loadLibrary` resolves through the calling
     * class's loader, and the LSPosed/Vector module loader answers with the packaged zip path
     * (`<apk>!/lib/<abi>/libcatsmoker_bridge.so`), which the linker maps without extraction.
     *
     * Preferred over [preload] in a target process because of what that load *triggers*: the
     * framework's native module API watches for it (the module declares the library in
     * `assets/native_init`) and calls the library's `native_init` entry point. Loading the
     * library any other way — an extracted copy on a different path — would leave the process
     * with a second, unregistered copy, so callers must try this route first and fall back to
     * [preload] only when it fails.
     *
     * In CatSmoker's own process this is the ordinary packaged-library load.
     */
    fun preloadFromClassLoader(): Boolean = ensureLoaded()

    /**
     * Loads the bridge from an explicit path — the fallback for a framework whose module loader
     * does not answer `findLibrary`: the module copies its own APK entry out and loads that path
     * as the target's UID, which maps a second copy and therefore skips `native_init`.
     * Idempotent; the first successful load wins.
     */
    @Synchronized
    fun preload(path: String): Boolean {
        if (loaded) return true
        val ok = runCatching {
            System.load(path)
            true
        }.getOrDefault(false)
        if (ok) {
            loaded = true
            loadAttempted = true
        }
        return ok
    }

    /** Build identity from the library itself, or null when it is absent or the call throws. */
    fun version(): String? =
        if (!ensureLoaded()) null else runCatching { nativeVersion() }.getOrNull()

    /**
     * M152 readiness probe: true when `libil2cpp.so` is already mapped in this process.
     *
     * Cheap and non-blocking (a `/proc/self/maps` read, no retry loop), so a caller on the
     * engine's own thread can decide whether the icall path is safe to touch yet. A false
     * answer never means "not a Unity game", only "not loaded at this instant".
     */
    fun isIl2cppReady(): Boolean =
        ensureLoaded() && runCatching { nativeIl2cppReady() == 1 }.getOrDefault(false)

    /**
     * M152 read probe: the Unity `Application.targetFrameRate` of *this* process, or a
     * negative stage code (never a guessed rate). No write of any kind.
     */
    fun unityTargetFrameRate(): Int =
        if (!ensureLoaded()) NO_LIBRARY else runCatching { unityGetTargetFrameRate() }
            .getOrDefault(LIBRARY_CALL_FAILED)

    /**
     * M152 write half: invokes `Application.set_targetFrameRate(fps)` in *this* process.
     *
     * Returns 1 when the setter was invoked, else a negative stage code. Invocation is
     * all this reports — the effect is proven by the frame cadence afterwards.
     * Peak values only (a measured panel peak, never a cap); no opcode patching, no
     * resolution writes. Blocks up to ~10s when Unity has not loaded: call off the main
     * thread unless [isIl2cppReady] already answered true.
     */
    fun unitySetTargetFrameRate(fps: Int): Int =
        if (!ensureLoaded()) NO_LIBRARY else runCatching { nativeSetTargetFrameRate(fps) }
            .getOrDefault(LIBRARY_CALL_FAILED)

    @Synchronized
    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        if (loadAttempted) return false
        loadAttempted = true
        loaded = runCatching {
            System.loadLibrary("catsmoker_bridge")
            true
        }.getOrDefault(false)
        return loaded
    }

    private external fun nativeVersion(): String

    private external fun nativeIl2cppReady(): Int

    private external fun unityGetTargetFrameRate(): Int

    private external fun nativeSetTargetFrameRate(fps: Int): Int

    /** [unityTargetFrameRate] when this process has no bridge library (JVM hosts). */
    const val NO_LIBRARY = -4

    /** [unityTargetFrameRate] when the library is present but the call itself threw. */
    const val LIBRARY_CALL_FAILED = -5

    /** Native: libil2cpp.so never appeared — not a Unity/IL2CPP process, clean abort. */
    const val NO_IL2CPP = -1

    /** Native: il2cpp_resolve_icall missing — incompatible IL2CPP build, clean abort. */
    const val NO_RESOLVER = -2

    /** Native: get_targetFrameRate icall missing — clean abort. */
    const val NO_GETTER = -3
}
