package com.catsmoker.app.features.spoofdevice.root

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.WindowManager
import com.catsmoker.app.system.config.SpoofConfigProvider
import com.catsmoker.app.system.shell.NativeBridge
import de.robv.android.xposed.XposedBridge
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.roundToInt

/**
 * M152 delivery: raises a Unity game's internal frame-rate cap to the panel peak from
 * inside the game process (LSPosed scope + an assigned spoof profile, raise-only).
 *
 * Why this shape:
 * - An LSPosed module can load its own `.so` only through a loader that resolves it: the
 *   module class loader answers `System.loadLibrary` with the packaged zip path
 *   (`<apk>!/lib/<abi>/libcatsmoker_bridge.so`), and that is the load the framework's native
 *   module API watches for ([NativeBridge.preloadFromClassLoader]). A loader that cannot
 *   answer `findLibrary` leaves the fallback: read the library out of this module's own APK,
 *   write it into the target's cache as the target's UID and `System.load` that absolute path
 *   ([NativeBridge.preload]). The routes are tried in that order and never both — a second
 *   copy on another path is a copy no framework hook and no shared state knows about.
 * - The native call must run on an engine-attached thread. A raw background thread
 *   segfaults inside `libil2cpp` (device-proven on Helix Jump Unity 6, 2026-10-13), while
 *   a Unity game's engine loop lives on the Android main thread. Every attempt is posted
 *   to the main looper, after the cheap [NativeBridge.isIl2cppReady] probe says the
 *   library is mapped — so the call never hits the native 10 s retry loop on the UI thread.
 * - Raise-only by construction: the current `Application.targetFrameRate` is read first
 *   and the setter is called only when `0 < current < peak`. Unity's `-1` ("platform
 *   default", i.e. already uncapped) is left alone. No opcode patching, no resolution
 *   writes, no caps.
 *
 * A non-Unity process, a 32-bit process, an incompatible IL2CPP build or a missing icall
 * all end in a clean abort: the module simply does nothing. Nothing here is on by default
 * for unassigned apps — [schedule] is called only after a spoof profile resolved.
 *
 * Target-process containment: only the target's own `Context` is used (its content
 * resolver and code cache). This never touches CatSmoker's Hilt graph or its files.
 */
object UnityFrameRateDelivery {

    private const val TAG = "CatsmokerUnity"

    /** Retry points (ms after process attach). Unity titles boot at very different speeds. */
    private val RETRY_DELAYS_MS = longArrayOf(15_000, 30_000, 60_000, 120_000)

    /** ABIs the bridge parser supports (64-bit ELF only — the C reader is Elf64). */
    private val SUPPORTED_ABIS = arrayOf("arm64-v8a", "x86_64")

    @Volatile
    private var scheduled = false

    /**
     * Schedules one raise-only unlock attempt chain for this target process. Loads the bridge
     * and posts — no engine work on the caller's thread, so it is safe from `Application.attach`.
     * A second call in the same process is a no-op. [context] must be the target application
     * context.
     */
    fun schedule(context: Context, isActive: () -> Boolean = { true }) {
        if (scheduled) return
        synchronized(this) {
            if (scheduled) return
            scheduled = true
        }
        if (!Process.is64Bit()) {
            log("32-bit process, bridge is 64-bit only — skipping")
            return
        }
        val appContext = context.applicationContext ?: context
        if (!loadBridge(appContext)) return
        val peak = panelPeak(appContext)
        if (peak <= 0) {
            log("panel peak unreadable — skipping")
            return
        }
        val handler = Handler(Looper.getMainLooper())
        val attempt = object : Runnable {
            private var index = 0

            override fun run() {
                if (!isActive()) {
                    log("profile unassigned — stopping delivery")
                    return
                }
                if (tryUnlock(peak)) return
                if (index >= RETRY_DELAYS_MS.lastIndex) {
                    log("no Unity icall path found — leaving the game alone")
                    return
                }
                handler.postDelayed(this, RETRY_DELAYS_MS[++index] - RETRY_DELAYS_MS[index - 1])
            }
        }
        handler.postDelayed(attempt, RETRY_DELAYS_MS[0])
    }

    /**
     * Loads the bridge once, by the module route first and the extracted path only if that
     * fails. Returns true when the library is in this process afterwards.
     *
     * The order matters beyond convenience: the class-loader load is the one the framework's
     * native module API watches for, and it is the copy the spoof channel's `native_init` and
     * every JNI setter live in. Loading the extracted path first would give the process two
     * copies with two sets of native state.
     */
    private fun loadBridge(context: Context): Boolean {
        if (NativeBridge.preloadFromClassLoader()) return true
        val path = runCatching { extractBridge(context) }.getOrNull()
        if (path == null) {
            log("could not stage the bridge from the module APK — skipping")
            return false
        }
        if (!NativeBridge.preload(path)) {
            log("the bridge did not load from $path — skipping")
            return false
        }
        return true
    }

    /**
     * One attempt on the main thread. Returns true when the chain reached a terminal state
     * (setter invoked, already at/above peak, or a definitive native abort) and no further
     * retry is useful; false only while Unity has not loaded yet.
     */
    private fun tryUnlock(peak: Int): Boolean {
        // load() is idempotent; the first attempt may be the one that succeeds.
        if (!NativeBridge.isAvailable()) return true
        if (!NativeBridge.isIl2cppReady()) return false
        val current = NativeBridge.unityTargetFrameRate()
        if (current <= 0) {
            // -1 = Unity's "platform default" (already uncapped); the other negatives are
            // definitive stage codes (-2 incompatible build, -3 icall missing).
            log("targetFrameRate=$current — nothing to raise")
            return true
        }
        if (current >= peak) {
            log("targetFrameRate=$current already at panel peak $peak")
            return true
        }
        val result = NativeBridge.unitySetTargetFrameRate(peak)
        log("targetFrameRate $current -> $peak (setter result $result)")
        return true
    }

    /**
     * Copies `lib/<abi>/libcatsmoker_bridge.so` out of this module's own APK into the
     * target's code-cache directory and returns the absolute path. Re-extracted when the
     * size differs (an app update), so a stale copy cannot be loaded after a version bump.
     * Only reached when the class-loader route failed (see [loadBridge]).
     */
    private fun extractBridge(context: Context): String? {
        val apk = findOwnApk(context) ?: return null
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in SUPPORTED_ABIS } ?: return null
        val entryName = "lib/$abi/libcatsmoker_bridge.so"
        ZipFile(apk).use { zip ->
            val entry = zip.getEntry(entryName) ?: return null
            val dir = File(context.codeCacheDir, "native").apply { mkdirs() }
            val apkFile = File(apk)
            val stamp = "${apkFile.length()}-${apkFile.lastModified()}"
            val out = File(dir, "libcatsmoker_bridge-$abi-$stamp.so")
            if (!out.exists() || out.length() != entry.size) {
                zip.getInputStream(entry).use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
                out.setReadable(true, false)
                out.setExecutable(true, false)
            }
            // Superseded copies from older APK builds are safe to delete: a loaded .so keeps
            // its inode, and no other process should use an old stamp.
            dir.listFiles { f ->
                f.name.startsWith("libcatsmoker_bridge-$abi-") && f.name != out.name
            }?.forEach { runCatching { it.delete() } }
            return out.absolutePath
        }
    }

    /**
     * Locates this module's APK from inside the target process:
     * 1. the config provider (authoritative under LSPosed/Vector, which may load module
     *    code from memory and leave no APK path in the classloader);
     * 2. the class's own code source;
     * 3. the module classloader's dex path list.
     * Any failure returns null; the caller aborts without side effects.
     */
    private fun findOwnApk(context: Context): String? {
        runCatching {
            val bundle = context.contentResolver.call(
                SpoofConfigProvider.CONFIG_URI,
                SpoofConfigProvider.METHOD_GET_MODULE_APK,
                null,
                null
            )
            val path = bundle?.getString(SpoofConfigProvider.COLUMN_APK)
            if (path != null && File(path).isFile) return path
        }
        runCatching {
            val location = UnityFrameRateDelivery::class.java.protectionDomain
                ?.codeSource?.location ?: return@runCatching
            val file = File(location.toURI())
            if (file.isFile && file.name.endsWith(".apk")) return file.absolutePath
        }
        runCatching {
            var loader: ClassLoader? = UnityFrameRateDelivery::class.java.classLoader
            while (loader != null) {
                val pathList = runCatching {
                    Class.forName("dalvik.system.BaseDexClassLoader", false, loader)
                        .getDeclaredField("pathList").apply { isAccessible = true }
                        .get(loader)
                }.getOrNull()
                val elements = runCatching {
                    pathList?.javaClass?.getDeclaredField("dexElements")
                        ?.apply { isAccessible = true }?.get(pathList)
                }.getOrNull() as? Array<*>
                elements?.forEach { element ->
                    val path = runCatching {
                        element?.javaClass?.getDeclaredField("path")
                            ?.apply { isAccessible = true }?.get(element) as? File
                    }.getOrNull()
                    if (path?.isFile == true && path.name.endsWith(".apk") &&
                        path.absolutePath.contains(OWN_PACKAGE)
                    ) {
                        return path.absolutePath
                    }
                }
                loader = loader.parent
            }
        }
        return null
    }

    /** Highest supported display mode rate (the spoof hook may narrow the list to its tier). */
    private fun panelPeak(context: Context): Int {
        val display = runCatching {
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        }.getOrNull() ?: return 0
        val rates = runCatching { display.supportedModes.map { it.refreshRate } }
            .getOrDefault(emptyList())
        val peak = rates.maxOrNull() ?: runCatching { display.refreshRate }.getOrDefault(0f)
        return peak.roundToInt()
    }

    private const val OWN_PACKAGE = "com.catsmoker.app"

    /** Logcat for the app-side log; LSPosed log too, where a field report can reach it. */
    private fun log(message: String) {
        Log.i(TAG, message)
        runCatching { XposedBridge.log("$TAG: $message") }
    }
}
