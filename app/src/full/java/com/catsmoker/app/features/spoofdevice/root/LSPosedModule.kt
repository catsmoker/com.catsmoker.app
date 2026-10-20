package com.catsmoker.app.features.spoofdevice.root

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.view.Display
import com.catsmoker.app.shared.data.model.LSPosedConfig
import com.catsmoker.app.system.config.SpoofConfigProvider
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Method
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Keeps the refresh rates at the spoofed tier from a list of panel rates.
 *
 * Panels report nominal rates with jitter (`119.999985` is a 120 Hz mode), so matching uses
 * [RATE_TOLERANCE_HZ]. An empty result means "leave the list alone" — the hook returns the
 * original modes instead of handing the game zero display modes. A non-positive spoof rate
 * disables the filter the same way a blank profile key does.
 */
fun filterRefreshRates(rates: List<Float>, spoofHz: Float): List<Float> {
    if (spoofHz <= 0f) return emptyList()
    return rates.filter { abs(it - spoofHz) <= RATE_TOLERANCE_HZ }
}

/** Nominal-rate jitter panels report (`119.999985` is a 120 Hz mode). */
private const val RATE_TOLERANCE_HZ = 0.5f

/**
 * Applies the spoof profile the user assigned to *this* app, inside its own process.
 *
 * Profiles are the same `key=value` text
 * [com.catsmoker.app.shared.data.repository.SpoofRepository.renderConfig] writes for Magisk, so
 * system-property names are the only vocabulary: `Build.MODEL` comes from `ro.product.model`.
 *
 * An app with no assigned profile is left completely alone — no hooks are installed at all.
 */
class LSPosedModule : IXposedHookLoadPackage {

    /** Resolved profile for this process. Empty means "do not spoof this app". */
    @Volatile
    private var props: Map<String, String> = emptyMap()

    private var hooksInstalled = false
    private var receiverRegistered = false

    /**
     * Original Build values, captured before the first overwrite. Without these, un-assigning a
     * profile could not take effect until the target app was killed.
     */
    private val originalFields = HashMap<String, Any?>()

    /** Read before anything is spoofed, so our own platform checks stay honest. */
    private val realSdkInt = Build.VERSION.SDK_INT

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        val pkg = lpparam.packageName
        if (pkg == "android") return
        // Our own process is never spoofed — but its load still records a heartbeat
        // as one of the module-active signals the status card reads (there is no API
        // to ask LSPosed "am I active"). Our package needs no scope entry for this:
        // Vector always loads a module into its own app ("Always in scope"), and
        // loads into scoped target processes are observed from the daemon log too.
        // A load here installs nothing else.
        if (pkg == OWN_PACKAGE) {
            markOwnProcessLoaded(lpparam.classLoader)
            return
        }

        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.args[0] as? Context ?: return
                        registerReceiver(context, lpparam)
                        reload(context, lpparam)
                    }
                })
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: cannot hook Application.attach in $pkg: ${e.message}")
        }
    }

    // ------------------------------------------------------------------- config

    /**
     * Records that the module loaded into our own process, as one of the signals the
     * app uses to answer "is the module active" without asking LSPosed (which offers
     * no such query). Always reachable: Vector loads every module into its own app
     * ("Always in scope") regardless of the recommended scope array, so this fires
     * with no scope entry of ours anywhere. Detection still never depends on it
     * alone — framework / manager / daemon-log evidence is read too
     * (see `VectorActivation`).
     *
     * Hooks `Application.attach` only to learn a Context, then writes one
     * boot-relative timestamp into our own prefs and stops: no profile is read and
     * no spoof hook is installed here, so our own process keeps its real identity
     * (own platform checks, attestation reads and the config provider itself must
     * never see spoofed values). The write is best-effort —
     * a missing heartbeat reads as "not loaded", never as a crash.
     */
    private fun markOwnProcessLoaded(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                classLoader,
                "attach",
                Context::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val context = param.args[0] as? Context ?: return
                        try {
                            context.getSharedPreferences(
                                LSPosedConfig.PREFS_NAME, Context.MODE_PRIVATE
                            ).edit()
                                .putLong(
                                    LSPosedConfig.KEY_MODULE_HEARTBEAT_ELAPSED,
                                    SystemClock.elapsedRealtime()
                                )
                                .apply()
                        } catch (_: Throwable) {
                            // Best-effort: the status card treats absence as "not loaded".
                        }
                        XposedBridge.log("$TAG: module loaded in own process, heartbeat recorded")
                    }
                })
        } catch (e: Throwable) {
            XposedBridge.log("$TAG: cannot hook Application.attach in own process: ${e.message}")
        }
    }

    private fun reload(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        val resolved = readConfig(context, lpparam.packageName)
        props = resolved

        if (resolved.isEmpty()) {
            // Put back every field we previously overwrote. Installed method hooks stay in place
            // but read `props`, so they become pass-throughs; the native table clears the same
            // way, without unloading anything.
            if (hooksInstalled) applyBuildFields(emptyMap())
            if (hooksInstalled) NativeSpoofChannel.install(emptyMap())
            return
        }

        applyBuildFields(resolved)
        installHooks(lpparam.classLoader)
        NativeSpoofChannel.install(resolved)
        UnityFrameRateDelivery.schedule(context) { props.isNotEmpty() }
        XposedBridge.log("$TAG: spoofing ${lpparam.packageName} as ${resolved["ro.product.model"]}")
    }

    /**
     * Resolves this package's profile, preferring the freshest channel that actually works.
     *
     * @return the parsed profile, or an empty map when the app should not be touched.
     */
    private fun readConfig(context: Context, packageName: String): Map<String, String> {
        // Checked before any channel is consulted so it cannot be overridden by an assignment —
        // the same hard floor as safe mode, but one the user cannot tick their way out of.
        if (packageName in LSPosedConfig.NEVER_SPOOF_PACKAGES) {
            XposedBridge.log("$TAG: $packageName is on the never-spoof list, leaving it alone")
            return emptyMap()
        }

        val text = queryProvider(context, packageName)
            ?: sectionFromGlobal(context.contentResolver, packageName)
            ?: legacyGlobal(context.contentResolver, packageName)
            ?: return emptyMap()

        val parsed = LSPosedConfig.parseDeviceProps(text)
        if (parsed.isEmpty()) return emptyMap()

        // The two per-package user lists, resolved in one place: safe mode skips the package
        // entirely, version-only keeps everything but the Android version properties (so the
        // maps below and every hook reading them answer the real values). An empty result is
        // only ever the safe-mode branch — a version-only strip always keeps the list key itself.
        val gated = LSPosedConfig.applyUserLists(parsed, packageName)
        if (gated.isEmpty()) {
            XposedBridge.log("$TAG: $packageName is in safe mode, leaving it alone")
            return emptyMap()
        }
        if (gated !== parsed) {
            XposedBridge.log("$TAG: $packageName keeps the real Android version (version-only bypass)")
        }
        // Seed-derived per-app IDs (M146): the single injection point after the user-list gate,
        // so every consumer of `props` — the Settings.Secure hook, Build.getSerial, the
        // SystemProperties hooks and the getprop dump merge — reads one map and cannot disagree.
        // Literal fields win inside; identity comparison drives the log, so a no-op is silent.
        val derived = AndroidIdDerivation.withDerivedIds(gated, packageName)
        if (derived !== gated) {
            XposedBridge.log("$TAG: $packageName derives per-app IDs from the profile seed")
        }
        return derived
    }

    /** Freshest source, but package-visibility filtering hides our authority from most apps. */
    private fun queryProvider(context: Context, packageName: String): String? = try {
        context.contentResolver.call(
            SpoofConfigProvider.CONFIG_URI,
            SpoofConfigProvider.METHOD_GET_CONFIG,
            packageName,
            null
        )?.getString(SpoofConfigProvider.COLUMN_CONTENT)?.ifBlank { null }
    } catch (_: Throwable) {
        null
    }

    /** Per-package section of the Settings.Global document — readable from any process. */
    private fun sectionFromGlobal(resolver: ContentResolver, packageName: String): String? =
        LSPosedConfig.parseSectionWildcard(
            readGlobal(resolver, LSPosedConfig.KEY_GLOBAL_PROFILES_B64),
            packageName
        )

    /**
     * Single-profile blob from older builds. Gated on the explicit target list: without that gate
     * this path spoofed every process the module was loaded into.
     */
    private fun legacyGlobal(resolver: ContentResolver, packageName: String): String? {
        val targets = LSPosedConfig.parseTargetPackages(
            readGlobal(resolver, LSPosedConfig.KEY_GLOBAL_TARGET_PACKAGES_B64)
        )
        if (packageName !in targets) return null
        return readGlobal(resolver, LSPosedConfig.KEY_GLOBAL_DEVICE_PROPS_B64)
    }

    private fun readGlobal(resolver: ContentResolver, key: String): String? = try {
        val raw = Settings.Global.getString(resolver, key)
        if (raw.isNullOrEmpty()) null
        else String(Base64.decode(raw, Base64.NO_WRAP), Charsets.UTF_8).ifBlank { null }
    } catch (_: Throwable) {
        null
    }

    private fun registerReceiver(context: Context, lpparam: XC_LoadPackage.LoadPackageParam) {
        if (receiverRegistered) return
        receiverRegistered = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = reload(context, lpparam)
        }
        val filter = IntentFilter(LSPosedConfig.ACTION_CONFIG_CHANGED)
        try {
            if (realSdkInt >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (_: Throwable) {
            receiverRegistered = false
        }
    }

    // -------------------------------------------------------------------- hooks

    /** Method hooks read [props] on every call, so they only ever need installing once. */
    private fun installHooks(classLoader: ClassLoader) {
        if (hooksInstalled) return
        hooksInstalled = true
        hookSystemProperties(classLoader)
        hookSettingsSecure(classLoader)
        hookTelephony(classLoader)
        hookTelephonyCoherence(classLoader)
        hookPackageManagerFeatures(classLoader)
        hookSemSystemProperties(classLoader)
        hookScreenMetrics(classLoader)
        hookBuildMethods(classLoader)
        hookWebView(classLoader)
        hookJavaSystemProperties()
        hookLocaleAndTimeZone()
        hookMediaDrm(classLoader)
        hookAdvertisingId(classLoader)
        hookAppSetId(classLoader)
        hookGlStrings()
        hookDisplayRefreshRate()
        hookEmulatorArtifacts()
        hookHardwareFacts(classLoader)
        GetPropInterceptor(lookup = ::lookupProp, properties = { props }).install()
    }

    private fun applyBuildFields(props: Map<String, String>) {
        for ((field, keys) in BUILD_FIELDS) {
            setStaticString(Build::class.java, "Build.$field", field, keys.firstNotNullOfOrNull(props::get))
        }
        for ((field, keys) in VERSION_FIELDS) {
            setStaticString(VERSION_CLASS, "VERSION.$field", field, keys.firstNotNullOfOrNull(props::get))
        }
        for ((field, key) in ABI_LIST_FIELDS) {
            setStaticAbiList(field, props[key])
        }
        applySdkInt(props["ro.build.version.sdk"]?.toIntOrNull())
    }

    /** Writes [value], or restores the captured original when it is null. */
    private fun setStaticString(clazz: Class<*>, tag: String, field: String, value: String?) {
        try {
            if (!originalFields.containsKey(tag)) {
                originalFields[tag] = XposedHelpers.getStaticObjectField(clazz, field)
            }
            XposedHelpers.setStaticObjectField(clazz, field, value ?: originalFields[tag])
        } catch (_: Throwable) {
        }
    }

    private fun applySdkInt(value: Int?) {
        try {
            if (!originalFields.containsKey(TAG_SDK_INT)) {
                originalFields[TAG_SDK_INT] = XposedHelpers.getStaticIntField(VERSION_CLASS, "SDK_INT")
            }
            val target = value ?: originalFields[TAG_SDK_INT] as? Int ?: return
            XposedHelpers.setStaticIntField(VERSION_CLASS, "SDK_INT", target)
        } catch (_: Throwable) {
        }
    }

    /** The `SUPPORTED_*_ABIS` fields are `String[]`, so the CSV from the profile has to be split. */
    private fun setStaticAbiList(field: String, csv: String?) {
        val tag = "Build.$field"
        try {
            if (!originalFields.containsKey(tag)) {
                originalFields[tag] = XposedHelpers.getStaticObjectField(Build::class.java, field)
            }
            val value = csv
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
                ?.toTypedArray()
                ?: originalFields[tag]
            XposedHelpers.setStaticObjectField(Build::class.java, field, value)
        } catch (_: Throwable) {
        }
    }

    private fun hookSystemProperties(classLoader: ClassLoader) {
        // Loader attempts: the app loader, the bootstrap loader, then a runtime lookup that
        // never touches the compile classpath (the class is hidden from it). Any unhooked
        // shape reopens the SystemProperties-vs-subprocess disagreement the getprop
        // interceptor closes, so every miss is retried through the next loader; repeat hits
        // on the same Class are skipped (the hook body is idempotent, but thrice per call
        // is still waste).
        hookSystemPropertiesClass(
            XposedHelpers.findClassIfExists("android.os.SystemProperties", classLoader)
        )
        hookSystemPropertiesClass(
            XposedHelpers.findClassIfExists("android.os.SystemProperties", null)
        )
        hookSystemPropertiesClass(
            runCatching { Class.forName("android.os.SystemProperties") }.getOrNull()
        )
    }

    private val hookedSystemPropertiesClasses = HashSet<Class<*>>()

    /**
     * Intercepts Samsung's `SemSystemProperties` wrappers with the same get/getInt/getLong/
     * getBoolean shape as [hookSystemPropertiesClass]: Samsung readers bypass
     * `android.os.SystemProperties`, which would otherwise reopen the channel disagreement
     * on exactly the devices whose readers use it. Device-specific and conditional — both
     * class names, both loaders, every install guarded, a miss is a no-op on non-Samsung
     * builds. Ported from the reference `VendorSystemPropertiesHooks` (minus its
     * version-bypass, which this app tracks separately).
     */
    private fun hookSemSystemProperties(classLoader: ClassLoader) {
        for (className in SEM_SYSTEM_PROPERTIES_CLASSES) {
            hookSystemPropertiesClass(XposedHelpers.findClassIfExists(className, classLoader))
            hookSystemPropertiesClass(XposedHelpers.findClassIfExists(className, null))
        }
    }

    /**
     * Rewrites display metrics, size getters, bounds and Configuration from the profile's
     * screen block — the display half of form-factor coherence (a tablet profile beside a
     * phone-sized `screenWidthDp` is self-contradictory).
     *
     * Gated on the explicit `device.apply_screen_metrics` opt-in (blank/off by default):
     * geometry changes what a game renders, so filling the screen fields in never applies
     * them as a side effect. Adapted from the reference `DisplayHooks` (read in full):
     * Resources + Display + WindowMetrics surfaces with the same formulas (see
     * [SpoofDisplayMetrics]); unassigning returns new reads to pass-through (already-handed
     * copies in the target keep their values, like the reference).
     */
    private fun hookScreenMetrics(classLoader: ClassLoader) {
        val metricsHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!SpoofDisplayMetrics.shouldApply(props)) return
                val spec = SpoofDisplayMetrics.specFromProps(props) ?: return
                val metrics = param.result as? android.util.DisplayMetrics ?: return
                SpoofDisplayMetrics.applyMetrics(
                    metrics,
                    spec.width.takeIf { it > 0 },
                    spec.height.takeIf { it > 0 },
                    spec.densityDpi.takeIf { it > 0 }
                )
            }
        }
        try {
            val resClass = XposedHelpers.findClassIfExists("android.content.res.Resources", null)
                ?: return
            XposedHelpers.findAndHookMethod(resClass, "getDisplayMetrics", metricsHook)
        } catch (_: Throwable) {
        }
        val configHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!SpoofDisplayMetrics.shouldApply(props)) return
                val spec = SpoofDisplayMetrics.specFromProps(props) ?: return
                val config = param.result as? android.content.res.Configuration ?: return
                SpoofDisplayMetrics.applyConfiguration(
                    config,
                    spec.width.takeIf { it > 0 },
                    spec.height.takeIf { it > 0 },
                    spec.densityDpi.takeIf { it > 0 }
                )
            }
        }
        try {
            val resClass = XposedHelpers.findClassIfExists("android.content.res.Resources", null)
                ?: return
            XposedHelpers.findAndHookMethod(resClass, "getConfiguration", configHook)
        } catch (_: Throwable) {
        }

        val displayClass = XposedHelpers.findClassIfExists("android.view.Display", classLoader)
            ?: try {
                android.view.Display::class.java
            } catch (_: Throwable) {
                return
            }
        val pointHook = { getPoint: (XC_MethodHook.MethodHookParam) -> android.graphics.Point? ->
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!SpoofDisplayMetrics.shouldApply(props)) return
                    val spec = SpoofDisplayMetrics.specFromProps(props) ?: return
                    val point = getPoint(param) ?: return
                    SpoofDisplayMetrics.applyPoint(
                        point,
                        spec.width.takeIf { it > 0 },
                        spec.height.takeIf { it > 0 }
                    )
                }
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                displayClass, "getMetrics", android.util.DisplayMetrics::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!SpoofDisplayMetrics.shouldApply(props)) return
                        val spec = SpoofDisplayMetrics.specFromProps(props) ?: return
                        val metrics = param.args.getOrNull(0) as? android.util.DisplayMetrics ?: return
                        SpoofDisplayMetrics.applyMetrics(
                            metrics,
                            spec.width.takeIf { it > 0 },
                            spec.height.takeIf { it > 0 },
                            spec.densityDpi.takeIf { it > 0 }
                        )
                    }
                }
            )
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.findAndHookMethod(
                displayClass, "getRealMetrics", android.util.DisplayMetrics::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!SpoofDisplayMetrics.shouldApply(props)) return
                        val spec = SpoofDisplayMetrics.specFromProps(props) ?: return
                        val metrics = param.args.getOrNull(0) as? android.util.DisplayMetrics ?: return
                        SpoofDisplayMetrics.applyMetrics(
                            metrics,
                            spec.width.takeIf { it > 0 },
                            spec.height.takeIf { it > 0 },
                            spec.densityDpi.takeIf { it > 0 }
                        )
                    }
                }
            )
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.findAndHookMethod(
                displayClass, "getSize", android.graphics.Point::class.java, pointHook { it.args.getOrNull(0) as? android.graphics.Point }
            )
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.findAndHookMethod(
                displayClass, "getRealSize", android.graphics.Point::class.java, pointHook { it.args.getOrNull(0) as? android.graphics.Point }
            )
        } catch (_: Throwable) {
        }
        val dimensionHook = { width: Boolean ->
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (!SpoofDisplayMetrics.shouldApply(props)) return
                    val spec = SpoofDisplayMetrics.specFromProps(props) ?: return
                    val value = (if (width) spec.width else spec.height).takeIf { it > 0 } ?: return
                    param.result = value
                }
            }
        }
        try {
            XposedHelpers.findAndHookMethod(displayClass, "getWidth", dimensionHook(true))
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.findAndHookMethod(displayClass, "getHeight", dimensionHook(false))
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.findAndHookMethod(
                displayClass, "getRectSize", android.graphics.Rect::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!SpoofDisplayMetrics.shouldApply(props)) return
                        val spec = SpoofDisplayMetrics.specFromProps(props) ?: return
                        val rect = param.args.getOrNull(0) as? android.graphics.Rect ?: return
                        SpoofDisplayMetrics.applyBounds(
                            rect,
                            spec.width.takeIf { it > 0 },
                            spec.height.takeIf { it > 0 }
                        )
                    }
                }
            )
        } catch (_: Throwable) {
        }
        try {
            val wmClass = XposedHelpers.findClassIfExists("android.view.WindowMetrics", classLoader)
                ?: return
            XposedHelpers.findAndHookMethod(
                wmClass, "getBounds",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!SpoofDisplayMetrics.shouldApply(props)) return
                        val spec = SpoofDisplayMetrics.specFromProps(props) ?: return
                        val bounds = param.result as? android.graphics.Rect ?: return
                        val spoofed = android.graphics.Rect(bounds)
                        SpoofDisplayMetrics.applyBounds(
                            spoofed,
                            spec.width.takeIf { it > 0 },
                            spec.height.takeIf { it > 0 }
                        )
                        param.result = spoofed
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    private fun hookSystemPropertiesClass(sysPropClass: Class<*>?) {
        if (sysPropClass == null || !hookedSystemPropertiesClasses.add(sysPropClass)) return
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = param.args.getOrNull(0) as? String ?: return
                val spoofed = lookupProp(key) ?: return
                val method = param.method as? Method
                param.result = when (method?.returnType) {
                    Boolean::class.javaPrimitiveType -> spoofed.equals("true", true) || spoofed == "1"
                    Int::class.javaPrimitiveType -> spoofed.toIntOrNull() ?: param.result
                    Long::class.javaPrimitiveType -> spoofed.toLongOrNull() ?: param.result
                    else -> spoofed
                }
            }
        }

        try {
            XposedHelpers.findAndHookMethod(sysPropClass, "get", String::class.java, hook)
            XposedHelpers.findAndHookMethod(sysPropClass, "get", String::class.java, String::class.java, hook)
            XposedHelpers.findAndHookMethod(sysPropClass, "getInt", String::class.java, Int::class.java, hook)
            XposedHelpers.findAndHookMethod(sysPropClass, "getLong", String::class.java, Long::class.java, hook)
            XposedHelpers.findAndHookMethod(sysPropClass, "getBoolean", String::class.java, Boolean::class.java, hook)
        } catch (_: Throwable) {
        }
    }

    /**
     * Spoofs `glGetString(GL_VENDOR)` / `glGetString(GL_RENDERER)` for the game's hardware checks.
     *
     * Unity and Unreal device-tier lookups on Android frequently read the GL strings from Java
     * through `GLES20.glGetString` (or GLES10/30/31/32 — the same static method exists in each
     * wrapper class), so a profile that changes `ro.product.model` but leaves the real GPU
     * answering "Adreno (TM) 640" is a model/GPU pair no shipped device ever had. That
     * disagreement is a stronger signal than either value alone, which is the same
     * both-channels-or-neither test [GetPropInterceptor] exists for.
     *
     * These are the *Java* wrapper classes, which is the half a Java-based tier lookup uses. The
     * other half — native code calling `glGetString` in `libGLESv2.so` / `libGLESv1_CM.so` — is
     * covered by [NativeSpoofChannel], which hooks the dispatch entry point in the GL wrapper
     * libraries once the target loads them: the same profile keys, the same pair-or-none rule.
     * EGL strings (`eglQueryString`) are platform strings and stay untouched on both sides.
     * Both keys are rendered together by `renderConfig` or not at all, so neither half can
     * answer a vendor from the profile beside a renderer from the real silicon.
     */
    private fun hookGlStrings() {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                when (param.args.getOrNull(0) as? Int) {
                    GL_VENDOR -> props[LSPosedConfig.KEY_GPU_VENDOR]?.let { param.result = it }
                    GL_RENDERER -> props[LSPosedConfig.KEY_GPU_RENDERER]?.let { param.result = it }
                }
            }
        }
        // Each GLES wrapper class declares its own static glGetString, and a target may call any
        // of them. Every install is individually guarded: GLES30+ do not exist below API 18 and
        // a missing class is a miss, not an error.
        for (glClass in listOf("android.opengl.GLES10", "android.opengl.GLES20",
                "android.opengl.GLES30", "android.opengl.GLES31", "android.opengl.GLES32")) {
            try {
                XposedHelpers.findAndHookMethod(
                    XposedHelpers.findClassIfExists(glClass, null) ?: continue,
                    "glGetString", Int::class.javaPrimitiveType, hook
                )
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Spoofs `Display.getRefreshRate()` with the profile's rate, so a game whose frame-rate menu
     * is gated on the *display's* reported rate lists the tiers the spoofed panel claims.
     *
     * Cross-checked against the reference project's `processHook.spoofRefreshRate` (read in full):
     * it guards on the profile carrying a `refreshrate`, parses it as a float, and installs
     * `XposedBridge.hookAllMethods(Display.class, "getRefreshRate", …)` whose `afterHookedMethod`
     * forces the result — a `NumberFormatException` logged and swallowed. This port follows all
     * of that, with the divergences the app's architecture dictates:
     * * The reference hardcodes a compiled DEVICE_MAP of package → device profile; here the
     *   mapping is the user's own assignments and frame-rate ladders, and the rate travels as a
     *   rendered profile key ([LSPosedConfig.KEY_SCREEN_REFRESH_RATE]) like every other value.
     * * The reference's per-device `refreshrate` strings (165/120 Hz) are its own model-table
     *   data — reference model tables are not preset candidates, so our presets leave the field
     *   blank and the hook stays out of their rendered profiles entirely.
     * * The reference's device-spoofing half (`spoofDeviceProperties`, Build-field reflection) is
     *   not re-ported: [applyBuildFields] already owns that with original-capture restore.
     * * HEAD of the reference replaced all of this with a remote-JSON fetch (per-device profiles
     *   downloaded at runtime, its README's "no extra configuration"); that design is not ported —
     *   our profiles are local, user-owned, and offline by construction.
     *
     * Why an int in the profile when the hook forces a float: Android reports nominal rates as
     * floats (`119.999985`), but every game gate compares against user-facing tiers (60/90/120),
     * so the editor collects whole Hz and the spoof answers the value as given — no rounding of
     * the panel's own jitter is inherited by the spoof.
     *
     * `Display` resolves via `Display::class.java` rather than a classloader lookup because it is
     * a public framework class present in every target process; a hookAllMethods miss (no such
     * method — not possible today, but not our promise either) is caught and treated as a miss,
     * not an error, like every other hook here.
     *
     * Mode-list readers are covered too: games that enumerate `getSupportedModes()` /
     * `getSupportedRefreshRates()` never call `getRefreshRate()`, so those answers are filtered
     * to the spoofed tier by [filterRefreshRates]. A filter that matches nothing keeps the real
     * list — handing a game zero display modes would break it.
     *
     * Resident-hook note: unlike the Build-field overwrite (a value swap), these hooks stay
     * loaded in the target process while it runs. That is the documented, factual difference —
     * the profile editor carries the matching warning, and blank still means off.
     */
    private fun hookDisplayRefreshRate() {
        val rateHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val rate = props[LSPosedConfig.KEY_SCREEN_REFRESH_RATE]?.toFloatOrNull() ?: return
                if (rate > 0f) param.result = rate
            }
        }
        try {
            XposedBridge.hookAllMethods(Display::class.java, "getRefreshRate", rateHook)
        } catch (_: Throwable) {
        }
        val modesHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val rate = props[LSPosedConfig.KEY_SCREEN_REFRESH_RATE]?.toFloatOrNull()
                        ?: return
                    if (rate <= 0f) return
                    // Star projection first (reifiable, no unchecked cast); anything
                    // non-Mode is dropped where the old erasure cast crashed it below.
                    val modes = (param.result as? Array<*>)
                        ?.filterIsInstance<Display.Mode>() ?: return
                    val kept = modes.filter {
                        abs(it.refreshRate - rate) <= RATE_TOLERANCE_HZ
                    }
                    if (kept.isNotEmpty()) param.result = kept.toTypedArray()
                } catch (_: Throwable) {
                }
            }
        }
        try {
            XposedBridge.hookAllMethods(Display::class.java, "getSupportedModes", modesHook)
        } catch (_: Throwable) {
        }
        // The float-array variant; name-hooked so older platforms without it simply miss.
        val ratesHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                try {
                    val rate = props[LSPosedConfig.KEY_SCREEN_REFRESH_RATE]?.toFloatOrNull()
                        ?: return
                    if (rate <= 0f) return
                    val rates = (param.result as? FloatArray)?.toList() ?: return
                    val kept = filterRefreshRates(rates, rate)
                    if (kept.isNotEmpty()) param.result = kept.toFloatArray()
                } catch (_: Throwable) {
                }
            }
        }
        try {
            XposedBridge.hookAllMethods(Display::class.java, "getSupportedRefreshRates", ratesHook)
        } catch (_: Throwable) {
        }
    }

    private fun hookSettingsSecure(classLoader: ClassLoader) {        val settingsSecure = XposedHelpers.findClassIfExists("android.provider.Settings\$Secure", classLoader) ?: return
        // ANDROID_ID plus the GSF half: the profile carries device.gsf_id but no hook consumed
        // it (a write-only field), while any GMS-adjacent reader asking for a gsf-flavored key
        // got the real one. Both getString arities, like the reference SettingsHooks.
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val name = param.args.getOrNull(1) as? String ?: return
                if (name == Settings.Secure.ANDROID_ID) {
                    props["ANDROID_ID"]?.let { param.result = it }
                    return
                }
                if (name == "gsf_id" || name.contains("gsf")) {
                    props["device.gsf_id"]?.let { param.result = it }
                }
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                settingsSecure, "getString", ContentResolver::class.java, String::class.java, hook
            )
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.findAndHookMethod(
                settingsSecure, "getString",
                ContentResolver::class.java, String::class.java, String::class.java, hook
            )
        } catch (_: Throwable) {
        }
    }

    private fun hookTelephony(classLoader: ClassLoader) {
        val telephonyManager =
            XposedHelpers.findClassIfExists("android.telephony.TelephonyManager", classLoader) ?: return
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val key = TELEPHONY_KEYS[param.method.name] ?: return
                props[key]?.let { param.result = it }
            }
        }

        for (method in TELEPHONY_KEYS.keys) {
            // Most of these have a no-arg form plus a subscription-id overload.
            try {
                XposedHelpers.findAndHookMethod(telephonyManager, method, hook)
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.findAndHookMethod(telephonyManager, method, Int::class.java, hook)
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Telephony coherence: a tablet profile answering `getImei` beside `PHONE_TYPE_NONE` is
     * the half-spoof mismatch the getprop interceptor's KDoc warns about, so form-factor
     * channels move together.
     *
     * Derived from the profile's own `ro.build.characteristics` (see [SpoofCoherence]) —
     * no new model: a tablet profile hides telephony (`PHONE_TYPE_NONE`, absent SIM,
     * empty subscriptions), a phone profile passes the device's real answers through
     * everywhere the profile carries no value (the value hooks above own the spoofed half).
     * MCC/MNC/country answer from the profile's operator numeric when present. Adapted from
     * the reference `TelephonyHooks` (read in full); deliberately not ported is its
     * *synthetic* `SubscriptionInfo` construction — there are no subscription fields in the
     * profile to synthesize from, and inventing MCC/MNC would be fabrication.
     */
    private fun hookTelephonyCoherence(classLoader: ClassLoader) {
        val telephonyManager =
            XposedHelpers.findClassIfExists("android.telephony.TelephonyManager", classLoader)
                ?: return
        val tablet: () -> Boolean = { SpoofCoherence.isTabletProfile(props) }

        fun hookInt(method: String, vararg params: Any, answer: () -> Int?) {
            try {
                XposedHelpers.findAndHookMethod(
                    telephonyManager, method, *params,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            answer()?.let { param.result = it }
                        }
                    }
                )
            } catch (_: Throwable) {
            }
        }

        fun hookBoolean(method: String, vararg params: Any, answer: () -> Boolean?) {
            try {
                XposedHelpers.findAndHookMethod(
                    telephonyManager, method, *params,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            answer()?.let { param.result = it }
                        }
                    }
                )
            } catch (_: Throwable) {
            }
        }

        hookInt("getPhoneType") { if (tablet()) android.telephony.TelephonyManager.PHONE_TYPE_NONE else null }
        hookBoolean("hasIccCard") { if (tablet()) false else null }
        hookBoolean("hasIccCard", Int::class.java) { if (tablet()) false else null }
        hookInt("getSimState") { if (tablet()) android.telephony.TelephonyManager.SIM_STATE_ABSENT else null }
        hookInt("getSimState", Int::class.java) { if (tablet()) android.telephony.TelephonyManager.SIM_STATE_ABSENT else null }
        hookInt("getSimCardState") { if (tablet()) android.telephony.TelephonyManager.SIM_STATE_ABSENT else null }
        hookInt("getSimApplicationState") { if (tablet()) android.telephony.TelephonyManager.SIM_STATE_ABSENT else null }
        hookInt("getPhoneCount") { if (tablet()) 0 else null }
        hookInt("getSimCount") { if (tablet()) 0 else null }
        hookBoolean("isVoiceCapable") { if (tablet()) false else null }
        hookBoolean("isSmsCapable") { if (tablet()) false else null }

        val mccMnc: () -> Pair<String?, String?> = {
            val numeric = (props["gsm.sim.operator.numeric"] ?: "").ifBlank { props["gsm.operator.numeric"] }
            if (numeric.isNullOrBlank()) null to null else SpoofCoherence.splitMccMnc(numeric)
        }
        fun hookString(method: String, answer: () -> String?) {
            try {
                XposedHelpers.findAndHookMethod(
                    telephonyManager, method,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            answer()?.let { param.result = it }
                        }
                    }
                )
            } catch (_: Throwable) {
            }
        }
        hookInt("getMcc") {
            if (tablet()) 0 else mccMnc().first?.toIntOrNull()
        }
        hookInt("getMnc") {
            if (tablet()) 0 else mccMnc().second?.toIntOrNull()
        }
        hookString("getMccString") {
            if (tablet()) "" else mccMnc().first
        }
        hookString("getMncString") {
            if (tablet()) "" else mccMnc().second
        }
        hookString("getSimCountryIso") {
            if (tablet()) "" else props["gsm.sim.operator.iso-country"]?.ifBlank { null }
        }
        hookString("getNetworkCountryIso") {
            if (tablet()) "" else props["gsm.sim.operator.iso-country"]?.ifBlank { null }
        }

        val subscriptionManager =
            XposedHelpers.findClassIfExists("android.telephony.SubscriptionManager", classLoader)
                ?: return
        fun subInt(method: String, vararg params: Any, answer: () -> Int?) {
            try {
                XposedHelpers.findAndHookMethod(
                    subscriptionManager, method, *params,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            answer()?.let { param.result = it }
                        }
                    }
                )
            } catch (_: Throwable) {
            }
        }
        subInt("getActiveSubscriptionInfoCount") { if (tablet()) 0 else null }
        subInt("getActiveSubscriptionInfoCountMax") { if (tablet()) 0 else null }
        try {
            XposedHelpers.findAndHookMethod(
                subscriptionManager, "getActiveSubscriptionIdList",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (tablet()) param.result = IntArray(0)
                    }
                }
            )
        } catch (_: Throwable) {
        }
        for (listMethod in listOf(
            "getActiveSubscriptionInfoList",
            "getCompleteActiveSubscriptionInfoList",
            "getAccessibleSubscriptionInfoList",
            "getAllSubscriptionInfoList"
        )) {
            try {
                XposedHelpers.findAndHookMethod(
                    subscriptionManager, listMethod,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            if (tablet()) param.result = emptyList<Any>()
                        }
                    }
                )
            } catch (_: Throwable) {
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                subscriptionManager, "getActiveSubscriptionInfo", Int::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (tablet()) param.result = null
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * PackageManager feature gating by form factor: a tablet profile beside `TELEPHONY=true`
     * is self-contradictory, so telephony features are denied on tablet builds and emulator
     * tells are always denied; everything else passes through. Adapted from the reference
     * `PackageManagerHooks` (read in full) with the same two sets and the same PC-type rule.
     */
    private fun hookPackageManagerFeatures(classLoader: ClassLoader) {
        val pmClass = XposedHelpers.findClassIfExists(
            "android.app.ApplicationPackageManager", classLoader
        ) ?: return
        val tablet: () -> Boolean = { SpoofCoherence.isTabletProfile(props) }
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val feature = param.args.getOrNull(0) as? String ?: return
                SpoofCoherence.overrideFeature(feature, tablet())?.let { param.result = it }
            }
        }
        try {
            XposedHelpers.findAndHookMethod(pmClass, "hasSystemFeature", String::class.java, hook)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.findAndHookMethod(
                pmClass, "hasSystemFeature", String::class.java, Int::class.java, hook
            )
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.findAndHookMethod(
                pmClass, "getSystemAvailableFeatures",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val features = param.result as? Array<*> ?: return
                        val componentType = features.javaClass.componentType ?: return
                        val kept = features.filter { feature ->
                            try {
                                val name = XposedHelpers.getObjectField(feature, "name") as? String
                                if (name == null) {
                                    true
                                } else {
                                    val override = SpoofCoherence.overrideFeature(name, tablet())
                                    override == null || override
                                }
                            } catch (_: Throwable) {
                                true
                            }
                        }
                        val remade = java.lang.reflect.Array.newInstance(componentType, kept.size)
                        kept.forEachIndexed { index, feature ->
                            java.lang.reflect.Array.set(remade, index, feature)
                        }
                        param.result = remade
                    }
                }
            )
        } catch (_: Throwable) {
        }
    }

    private fun hookBuildMethods(classLoader: ClassLoader) {
        val buildClass = XposedHelpers.findClassIfExists("android.os.Build", classLoader) ?: return
        // Build.SERIAL has been hardcoded to "unknown" since API 26; getSerial() is the live read.
        try {
            XposedHelpers.findAndHookMethod(buildClass, "getSerial", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    props["ro.serialno"]?.let { param.result = it }
                }
            })
        } catch (_: Throwable) {
        }
    }

    /**
     * M116: the emulator-file layer, gated per call on `emulator.hide=1` — installed once like
     * every other hook, no-op while the key is absent (the default). `java.io.File` probes are
     * the whole covered surface: `exists()` answers false and all three `listFiles` overloads
     * drop matching children (a null listing stays null, an emptied one stays an empty array).
     * Native opens and shelled-out listings bypass it entirely; [EmulatorArtifacts] holds the
     * exact predicate, its subtree bound and its honesty limits.
     */
    private fun hookEmulatorArtifacts() {
        try {
            XposedHelpers.findAndHookMethod(java.io.File::class.java, "exists", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val file = param.thisObject as? java.io.File ?: return
                    if (!emulatorFilesHidden()) return
                    if (EmulatorArtifacts.isHidden(file.absolutePath)) param.result = false
                }
            })
        } catch (_: Throwable) {
        }

        val listHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                if (!emulatorFilesHidden()) return
                @Suppress("UNCHECKED_CAST")
                val files = param.result as? Array<java.io.File> ?: return
                val kept = files.filter { !EmulatorArtifacts.isHidden(it.absolutePath) }
                if (kept.size != files.size) param.result = kept.toTypedArray()
            }
        }
        try {
            XposedHelpers.findAndHookMethod(java.io.File::class.java, "listFiles", listHook)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.findAndHookMethod(
                java.io.File::class.java, "listFiles", java.io.FileFilter::class.java, listHook
            )
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.findAndHookMethod(
                java.io.File::class.java, "listFiles", java.io.FilenameFilter::class.java, listHook
            )
        } catch (_: Throwable) {
        }
    }

    private fun emulatorFilesHidden(): Boolean =
        LSPosedConfig.isFlagEnabled(props[LSPosedConfig.KEY_EMULATOR_HIDE])

    /**
     * M117: user-declared hardware facts, each read per call — a hook does nothing while its
     * key is absent (the default), so installing once covers every later profile change.
     * Covered: `Runtime.availableProcessors`, `ActivityManager.getMemoryInfo` totalMem (the
     * out-param and returning shapes), `getMemoryClass`/`getLargeMemoryClass`, and
     * `RandomAccessFile.readLine` for `/proc/meminfo`. Deliberately not covered: native reads,
     * `Runtime.maxMemory`, `Debug.*`, `availMem`, and `cpuinfo` content — see
     * [SpoofHardwareFacts] for the full honesty list.
     */
    private fun hookHardwareFacts(classLoader: ClassLoader) {
        try {
            XposedHelpers.findAndHookMethod(Runtime::class.java, "availableProcessors", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    SpoofHardwareFacts.cpuCores(props)?.let { param.result = it }
                }
            })
        } catch (_: Throwable) {
        }

        val amClass = XposedHelpers.findClassIfExists("android.app.ActivityManager", classLoader)
        val memoryInfoClass = XposedHelpers.findClassIfExists(
            "android.app.ActivityManager\$MemoryInfo", classLoader
        )
        if (amClass != null) {
            if (memoryInfoClass != null) {
                val totalMemHook = object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ramMb = SpoofHardwareFacts.ramMb(props) ?: return
                        val info = (param.args.getOrNull(0) ?: param.result) ?: return
                        if (!memoryInfoClass.isInstance(info)) return
                        try {
                            XposedHelpers.setLongField(
                                info, "totalMem", SpoofHardwareFacts.ramBytes(ramMb)
                            )
                        } catch (_: Throwable) {
                        }
                    }
                }
                try {
                    XposedHelpers.findAndHookMethod(amClass, "getMemoryInfo", memoryInfoClass, totalMemHook)
                } catch (_: Throwable) {
                }
                // A returning-shape overload exists on some builds; a miss is a no-op elsewhere.
                try {
                    XposedHelpers.findAndHookMethod(amClass, "getMemoryInfo", totalMemHook)
                } catch (_: Throwable) {
                }
            }
            try {
                XposedHelpers.findAndHookMethod(amClass, "getMemoryClass", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        SpoofHardwareFacts.heapMb(props)?.let { param.result = it }
                    }
                })
            } catch (_: Throwable) {
            }
            try {
                XposedHelpers.findAndHookMethod(amClass, "getLargeMemoryClass", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        SpoofHardwareFacts.largeHeapMb(props)?.let { param.result = it }
                    }
                })
            } catch (_: Throwable) {
            }
        }

        try {
            XposedHelpers.findAndHookMethod(
                java.io.RandomAccessFile::class.java, "readLine", object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ramMb = SpoofHardwareFacts.ramMb(props) ?: return
                        val line = param.result as? String ?: return
                        param.result = SpoofHardwareFacts.rewriteMemTotal(
                            line, SpoofHardwareFacts.ramKb(ramMb)
                        )
                    }
                })
        } catch (_: Throwable) {
        }
    }

    private fun hookWebView(classLoader: ClassLoader) {
        val webSettings = XposedHelpers.findClassIfExists("android.webkit.WebSettings", classLoader) ?: return
        try {
            XposedHelpers.findAndHookMethod(
                webSettings, "getDefaultUserAgent", Context::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        props["webview.user_agent"]?.let { param.result = it }
                    }
                })
        } catch (_: Throwable) {
        }

        // Write-path supplement to the read hook above: a WebView that captured its settings
        // before any read gets the spoofed UA pushed in at construction and at getSettings.
        // Touching live objects is the documented cost — blank profile key means off, and every
        // set is guarded so a missing method is a miss, not a break. Adapted from the reference
        // WebViewHooks (read in full).
        val webViewClass = XposedHelpers.findClassIfExists("android.webkit.WebView", classLoader) ?: return
        val pushUa = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                val ua = props["webview.user_agent"] ?: return
                try {
                    val settings = XposedHelpers.callMethod(param.thisObject, "getSettings") ?: return
                    XposedHelpers.callMethod(settings, "setUserAgentString", ua)
                } catch (_: Throwable) {
                }
            }
        }
        try {
            XposedHelpers.findAndHookMethod(
                webViewClass, "getSettings",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val ua = props["webview.user_agent"] ?: return
                        try {
                            XposedHelpers.callMethod(param.result, "setUserAgentString", ua)
                        } catch (_: Throwable) {
                        }
                    }
                }
            )
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.findAndHookConstructor(webViewClass, Context::class.java, pushUa)
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.findAndHookConstructor(
                webViewClass, Context::class.java, android.util.AttributeSet::class.java, pushUa
            )
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.findAndHookConstructor(
                webViewClass,
                Context::class.java,
                android.util.AttributeSet::class.java,
                Int::class.javaPrimitiveType,
                pushUa
            )
        } catch (_: Throwable) {
        }
    }

    /**
     * `java.lang.System` mirrors a couple of values apps use as device metadata, and they are read
     * straight from a snapshot taken at VM start — hooking `SystemProperties` does not cover them.
     */
    private fun hookJavaSystemProperties() {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                when (param.args.getOrNull(0) as? String) {
                    "os.arch" -> javaOsArch()?.let { param.result = it }
                    "http.agent" -> props["webview.user_agent"]?.let { param.result = it }
                }
            }
        }
        try {
            XposedHelpers.findAndHookMethod(System::class.java, "getProperty", String::class.java, hook)
            XposedHelpers.findAndHookMethod(
                System::class.java, "getProperty", String::class.java, String::class.java, hook
            )
        } catch (_: Throwable) {
        }
    }

    /** `os.arch` uses the JVM's own spelling, which is not the Android ABI name. */
    private fun javaOsArch(): String? {
        val abi = props["ro.product.cpu.abi"] ?: return null
        return when {
            abi.contains("arm64") -> "aarch64"
            abi.contains("armeabi") -> "arm"
            abi.contains("x86_64") -> "x86_64"
            abi.contains("x86") -> "x86"
            else -> abi
        }
    }

    /**
     * `persist.sys.locale` and `persist.sys.timezone` are read once during zygote start, so the
     * property hooks never see them — the defaults have to be replaced directly.
     */
    private fun hookLocaleAndTimeZone() {
        val localeHook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                configuredLocale()?.let { param.result = it }
            }
        }
        try {
            XposedHelpers.findAndHookMethod(Locale::class.java, "getDefault", localeHook)
            XposedHelpers.findAndHookMethod(
                Locale::class.java, "getDefault", Locale.Category::class.java, localeHook
            )
        } catch (_: Throwable) {
        }

        try {
            XposedHelpers.findAndHookMethod(TimeZone::class.java, "getDefault", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val id = props["persist.sys.timezone"] ?: return
                    runCatching { TimeZone.getTimeZone(id) }.getOrNull()?.let { param.result = it }
                }
            })
        } catch (_: Throwable) {
        }

        try {
            XposedHelpers.findAndHookMethod(ZoneId::class.java, "systemDefault", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val id = props["persist.sys.timezone"] ?: return
                    runCatching { ZoneId.of(id) }.getOrNull()?.let { param.result = it }
                }
            })
        } catch (_: Throwable) {
        }
    }

    /** @return the profile's locale, or null when it is absent or not a usable language tag. */
    private fun configuredLocale(): Locale? {
        val tag = props["persist.sys.locale"] ?: return null
        val locale = runCatching { Locale.forLanguageTag(tag.replace('_', '-')) }.getOrNull() ?: return null
        return locale.takeIf { it.language.isNotEmpty() }
    }

    /** Widevine's `deviceUniqueId` is a common fingerprinting source that survives a factory reset. */
    private fun hookMediaDrm(classLoader: ClassLoader) {
        val mediaDrm = XposedHelpers.findClassIfExists("android.media.MediaDrm", classLoader) ?: return
        try {
            XposedHelpers.findAndHookMethod(
                mediaDrm, "getPropertyByteArray", String::class.java, object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args.getOrNull(0) as? String != MEDIA_DRM_UNIQUE_ID) return
                        decodeHex(props["device.media_drm_id"])?.let { param.result = it }
                    }
                })
        } catch (_: Throwable) {
        }
    }

    /** @return the decoded bytes, or null when [hex] is absent or not clean hex. */
    private fun decodeHex(hex: String?): ByteArray? {
        val clean = hex?.trim()?.removePrefix("0x")?.filterNot { it == ' ' || it == ':' } ?: return null
        if (clean.isEmpty() || clean.length % 2 != 0) return null
        return runCatching {
            ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        }.getOrNull()
    }

    /** Play Services classes: absent in most apps, so a miss here is normal rather than an error. */
    private fun hookAdvertisingId(classLoader: ClassLoader) {
        val hook = object : XC_MethodHook() {
            override fun afterHookedMethod(param: MethodHookParam) {
                props["device.gaid"]?.let { param.result = it }
            }
        }
        for (className in GAID_INFO_CLASSES) {
            val clazz = XposedHelpers.findClassIfExists(className, classLoader) ?: continue
            try {
                XposedHelpers.findAndHookMethod(clazz, "getId", hook)
            } catch (_: Throwable) {
            }
        }
    }

    private fun hookAppSetId(classLoader: ClassLoader) {
        val clazz = XposedHelpers.findClassIfExists(
            "com.google.android.gms.appset.AppSetIdInfo", classLoader
        ) ?: return
        try {
            XposedHelpers.findAndHookMethod(clazz, "getId", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    props["device.app_set_id"]?.let { param.result = it }
                }
            })
        } catch (_: Throwable) {
        }
        try {
            // A spoofed ID is per-app by construction, so report APP scope to match.
            XposedHelpers.findAndHookMethod(clazz, "getScope", object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    if (props.containsKey("device.app_set_id")) param.result = APP_SET_SCOPE_APP
                }
            })
        } catch (_: Throwable) {
        }
    }

    /**
     * Resolves a system-property read against the profile, accepting the partition and legacy
     * spellings of each key that the rendered profile does not list verbatim
     * ([PropAliases] — the same lists the native channel serializes).
     */
    private fun lookupProp(key: String): String? {
        val current = props
        if (current.isEmpty()) return null
        current[key]?.let { return it }
        current["prop.$key"]?.let { return it }
        PropAliases.DIRECT[key]?.let { canonical -> current[canonical]?.let { return it } }
        return PropAliases.suffixTarget(key)?.let { current[it] }
    }

    private companion object {
        const val TAG = "CatsmokerLSP"
        const val OWN_PACKAGE = "com.catsmoker.app"
        const val TAG_SDK_INT = "VERSION.SDK_INT"
        const val MEDIA_DRM_UNIQUE_ID = "deviceUniqueId"

        /**
         * Samsung's SystemProperties wrappers, both historical class names. Hooked with the
         * same get/getInt/getLong/getBoolean shape as the platform class because Samsung
         * readers bypass it — same lookup, same coercions, miss-is-no-op on other builds.
         */
        val SEM_SYSTEM_PROPERTIES_CLASSES = listOf(
            "android.os.SemSystemProperties",
            "com.samsung.android.os.SemSystemProperties"
        )

        /** `AppSetIdInfo.SCOPE_APP`, inlined because the GMS class is not on our classpath. */
        const val APP_SET_SCOPE_APP = 1

        /** GL enums, inlined so the hook file needs no android.opengl import. */
        const val GL_VENDOR = 0x1F00
        const val GL_RENDERER = 0x1F01

        val VERSION_CLASS: Class<*> = Build.VERSION::class.java

        /** Build field to the property keys that back it, most specific first. */
        val BUILD_FIELDS = listOf(
            "BRAND" to listOf("ro.product.brand"),
            "MANUFACTURER" to listOf("ro.product.manufacturer"),
            "MODEL" to listOf("ro.product.model"),
            "PRODUCT" to listOf("ro.product.name"),
            "DEVICE" to listOf("ro.product.device"),
            "BOARD" to listOf("ro.product.board"),
            "HARDWARE" to listOf("ro.hardware"),
            "FINGERPRINT" to listOf("ro.build.fingerprint"),
            "ID" to listOf("ro.build.id"),
            "DISPLAY" to listOf("ro.build.display.id", "ro.build.id"),
            "BOOTLOADER" to listOf("ro.bootloader"),
            "TYPE" to listOf("ro.build.type"),
            "TAGS" to listOf("ro.build.tags"),
            "SERIAL" to listOf("ro.serialno"),
            // Deprecated since API 21 but still read by native-loading and anti-emulator checks.
            "CPU_ABI" to listOf("ro.product.cpu.abi"),
            // SOC_* only exist from API 31; setStaticString swallows the miss on older platforms.
            "SOC_MODEL" to listOf("ro.soc.model"),
            "SOC_MANUFACTURER" to listOf("ro.soc.manufacturer")
        )

        /** `String[]` Build fields, backed by a comma-separated property. */
        val ABI_LIST_FIELDS = listOf(
            "SUPPORTED_ABIS" to "ro.product.cpu.abilist",
            "SUPPORTED_64_BIT_ABIS" to "ro.product.cpu.abilist64",
            "SUPPORTED_32_BIT_ABIS" to "ro.product.cpu.abilist32"
        )

        val VERSION_FIELDS = listOf(
            "RELEASE" to listOf("ro.build.version.release"),
            "RELEASE_OR_CODENAME" to listOf("ro.build.version.release"),
            "INCREMENTAL" to listOf("ro.build.version.incremental"),
            "SECURITY_PATCH" to listOf("ro.build.version.security_patch")
        )

        /** TelephonyManager method to the profile key that answers it. */
        val TELEPHONY_KEYS = mapOf(
            "getDeviceId" to "device.imei",
            "getImei" to "device.imei",
            "getMeid" to "device.meid",
            "getSubscriberId" to "device.imsi",
            "getSimSerialNumber" to "device.iccid",
            "getLine1Number" to "device.phone_number",
            "getNetworkOperatorName" to "gsm.operator.alpha",
            "getNetworkOperator" to "gsm.operator.numeric",
            "getSimOperatorName" to "gsm.sim.operator.alpha",
            "getSimOperator" to "gsm.sim.operator.numeric",
            "getSimCountryIso" to "gsm.sim.operator.iso-country",
            "getNetworkCountryIso" to "gsm.sim.operator.iso-country"
        )

        /**
         * Classes exposing a GAID `getId()`. The obfuscated one is the shape recent Play Services
         * builds ship; both are absent unless the app bundles the ads SDK.
         */
        val GAID_INFO_CLASSES = listOf(
            "com.google.android.gms.ads.identifier.AdvertisingIdClient\$Info",
            "com.google.android.gms.common.api.internal.zzx"
        )
    }
}
