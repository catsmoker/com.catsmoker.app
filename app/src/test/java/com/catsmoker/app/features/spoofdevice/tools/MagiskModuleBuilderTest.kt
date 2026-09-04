package com.catsmoker.app.features.spoofdevice.tools

import com.catsmoker.app.shared.data.model.LSPosedConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Test

/**
 * The Magisk module skeleton used to be five checked-in asset files; it is now generated, so these
 * tests are what the asset tree's mere existence used to guarantee.
 *
 * The stakes are why they exist at all: a wrong member name or a CR in a script produces a ZIP that
 * still installs and either does nothing or bricks a boot, on a device this suite cannot reach. The
 * two generated shell scripts now also *decide* things — which root manager is hosting the install,
 * which other module is fighting over the same properties, and whether the spoof actually landed —
 * so each of those branches is pinned here rather than left to be discovered after a flash.
 */
class MagiskModuleBuilderTest {

    private fun spec(
        props: Map<String, String> = linkedMapOf(
            "ro.product.model" to "Pixel 8 Pro",
            "ro.product.brand" to "google"
        ),
        profileName: String = "Pixel 8 Pro",
        model: String = "Pixel 8 Pro"
    ) = MagiskModuleBuilder.ModuleSpec(
        systemProperties = props,
        versionName = "2.0.0",
        versionCode = 6,
        profileName = profileName,
        spoofedModel = model
    )

    private fun build(spec: MagiskModuleBuilder.ModuleSpec): Map<String, String> {
        val bytes = ByteArrayOutputStream()
        MagiskModuleBuilder.write(bytes, spec)
        val members = linkedMapOf<String, String>()
        ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                members[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return members
    }

    // ------------------------------------------------------------------ skeleton

    @Test
    fun writesEveryMemberMagiskNeeds() {
        val members = build(spec())
        // Exact paths: Magisk locates its installer by these names, so a typo is a dead ZIP.
        assertTrue(members.containsKey("META-INF/com/google/android/update-binary"))
        assertTrue(members.containsKey("META-INF/com/google/android/updater-script"))
        assertTrue(members.containsKey("module.prop"))
        assertTrue(members.containsKey("system.prop"))
        assertTrue(members.containsKey("customize.sh"))
        // Magisk runs this at late_start by name; misspell it and the module silently stops
        // reporting whether the spoof applied.
        assertTrue(members.containsKey("service.sh"))
    }

    @Test
    fun reportsTheEntriesItActuallyWrote() {
        val bytes = ByteArrayOutputStream()
        val reported = MagiskModuleBuilder.write(bytes, spec())
        assertEquals(build(spec()).keys.toList(), reported)
    }

    @Test
    fun updaterScriptCarriesTheMagiskMarker() {
        assertEquals("#MAGISK\n", build(spec())["updater-script".let { "META-INF/com/google/android/$it" }])
    }

    /**
     * The reason `.gitattributes` had to pin these files while they were assets. A CR ahead of the
     * newline makes `#!/sbin/sh` a "bad interpreter" on Android.
     */
    @Test
    fun scriptsUseUnixLineEndingsOnly() {
        val members = build(spec())
        for (path in listOf(
            "META-INF/com/google/android/update-binary",
            "customize.sh",
            "service.sh"
        )) {
            assertFalse("CR found in $path", members.getValue(path).contains('\r'))
        }
    }

    @Test
    fun updateBinaryKeepsTheShellPositionalsLiteral() {
        val script = build(spec()).getValue("META-INF/com/google/android/update-binary")
        // Kotlin string templates would happily have eaten these.
        assertTrue(script.contains("OUTFD=\$2"))
        assertTrue(script.contains("ZIPFILE=\$3"))
        assertTrue(script.contains("echo \"\$1\""))
        assertTrue(script.contains("\$MAGISK_VER_CODE -lt ${MagiskModuleBuilder.MIN_MAGISK_VER_CODE}"))
        assertTrue(script.trimEnd().endsWith("exit 0"))
    }

    /**
     * KernelSU and APatch install this module format from their own manager, and the spoof screen
     * offers to open either one — but only Magisk ships `util_functions.sh`, so a user who reached
     * this script under one of them was told to "install Magisk v20.4+", which is both false and
     * unactionable. `referance/Magisk-Modules/FPS-Limitations-Patcher-v3.1` is the module that
     * treats all three as hosts.
     */
    @Test
    fun updateBinarySendsOtherRootManagersToTheirOwnInstaller() {
        val script = build(spec()).getValue("META-INF/com/google/android/update-binary")
        assertTrue(script.contains("require_own_manager \"KernelSU\""))
        assertTrue(script.contains("require_own_manager \"APatch\""))
        // Existence tests only: no path under either tree is sourced on an unverified layout.
        assertTrue(script.contains("-d /data/adb/ksu"))
        assertTrue(script.contains("-d /data/adb/ap"))
        assertFalse(script.contains(". /data/adb/ksu"))
        assertFalse(script.contains(". /data/adb/ap"))
        // The Magisk path is still the known-good template, untouched.
        assertTrue(script.contains(". /data/adb/magisk/util_functions.sh"))
        assertTrue(script.contains("install_module"))
    }

    // ------------------------------------------------------------------ customize.sh

    /**
     * `Unlocker-p4/customize.sh` prints `getprop ro.product.model`. Ours printed only the spoofed
     * model, which is the half the user already chose — the value being replaced is the other half.
     */
    @Test
    fun customizeShPrintsTheRealModelBesideTheSpoof() {
        val script = build(spec(model = "Pixel 8 Pro")).getValue("customize.sh")
        assertTrue(script.contains("\$(getprop ro.product.model)"))
        assertTrue(script.contains("Pixel 8 Pro"))
    }

    /** `install_module` *sources* this script, so an `exit` would abort the whole installer. */
    @Test
    fun customizeShNeverExitsTheInstaller() {
        val script = build(spec()).getValue("customize.sh")
        for (line in script.lineSequence()) {
            assertFalse("customize.sh must not exit: $line", line.trim().startsWith("exit"))
        }
    }

    /**
     * Two modules writing `ro.product.model` is last-one-wins at post-fs-data, and the loser is
     * silent. The scan compares real key sets rather than a curated list of module names, so it
     * cannot go stale — with the one exception `Unlocker-p4`'s README names by hand.
     */
    @Test
    fun customizeShNamesOtherModulesWritingTheSameKeys() {
        val script = build(spec()).getValue("customize.sh")
        assertTrue(script.contains("\${NVBASE:-/data/adb}/modules"))
        assertTrue(script.contains("grep -Fxf"))
        // A disabled module is not a conflict, and neither is the previous install of this one.
        assertTrue(script.contains("/disable\" ] && continue"))
        assertTrue(script.contains("\"\$OTHERID\" = \"\$MODID\" ] && continue"))
        assertTrue(script.contains("MagiskHidePropsConf"))
    }

    // ------------------------------------------------------------------ service.sh

    /**
     * The convention the rest of the app is built on — read the value back and report the measured
     * fact — reaching the one channel that had no way to report anything.
     */
    @Test
    fun serviceShReadsEveryPropertyBackAndSeparatesMissingFromOverwritten() {
        val script = build(spec()).getValue("service.sh")
        assertTrue(script.contains("GOT=\$(getprop \"\$KEY\")"))
        // "never created" and "something else won" need different fixes, so they stay distinct.
        assertTrue(script.contains("not set:"))
        assertTrue(script.contains("overwritten:"))
        assertTrue(script.contains(MagiskModuleBuilder.VERIFY_LOG_NAME))
        // Truncated, not appended: the log describes the boot the user is in.
        assertTrue(script.contains("> \"\$LOG\""))
    }

    /**
     * `cat file | while read` runs the loop in a subshell and every counter comes back zero — the
     * classic way a report like this silently reads "applied 0/0".
     */
    @Test
    fun serviceShCountersSurviveTheReadLoop() {
        val script = build(spec()).getValue("service.sh")
        assertTrue(script.contains("done < \"\$PROP\""))
        assertFalse(script.contains("| while"))
    }

    /** A device that never reports `sys.boot_completed` must not leave this looping forever. */
    @Test
    fun serviceShBoundsItsWaitAndRecordsATimeout() {
        val script = build(spec()).getValue("service.sh")
        assertTrue(script.contains("getprop sys.boot_completed"))
        assertTrue(script.contains("\"\$WAITED\" -ge 120"))
        assertTrue(script.contains("BOOTED=0"))
        // The timeout is reported, not assumed away.
        assertTrue(script.contains("readings may be early"))
    }

    /**
     * `service.sh` rewrites `module.prop` after every boot to report what applied, so it has to
     * reprint the identity exactly. If it drifted, the first reboot would silently revert the
     * module to a stale version or — worse, since Magisk keys modules by `id` — a different one.
     */
    @Test
    fun serviceShReprintsTheSameModuleIdentity() {
        val members = build(spec())
        val heredoc = members.getValue("service.sh")
            .substringAfter("<<'CATSMOKER_MODULE_PROP'\n")
            .substringBefore("\nCATSMOKER_MODULE_PROP")

        val reprinted = LSPosedConfig.parseDeviceProps(heredoc)
        val installed = LSPosedConfig.parseDeviceProps(members.getValue("module.prop"))

        assertEquals(installed - "description", reprinted)
        assertFalse(reprinted.containsKey("description"))
        // The description is the one line that must be computed on the device.
        assertTrue(members.getValue("service.sh").contains("echo \"description=\$DESC\""))
    }

    // ------------------------------------------------------------------ boot safety

    /**
     * The property set this channel is allowed to flash, and the reason the rest of the suite cares:
     * `system.prop` reaches `resetprop` at post-fs-data, device-wide, before the framework starts. A
     * `ro.hardware` or `ro.product.cpu.abi` that disagrees with the real silicon is not a weak spoof,
     * it is a device that does not boot — so the allowlist is pinned here rather than trusted to
     * survive the next person who wants "one more property".
     */
    @Test
    fun systemPropCarriesTheModelKeysAndNothingElse() {
        val full = linkedMapOf(
            "ro.product.brand" to "google",
            "ro.product.manufacturer" to "Google",
            "ro.product.model" to "GM45K",
            "ro.product.name" to "lynx",
            "ro.product.device" to "lynx",
            "ro.product.board" to "lynx",
            "ro.hardware" to "google",
            "ro.board.platform" to "gs401",
            "ro.build.fingerprint" to "google/lynx/lynx:17/UP1A.260805.001/1:user/release-keys",
            "ro.build.id" to "UP1A.260805.001",
            "ro.product.cpu.abi" to "arm64-v8a",
            "ro.product.cpu.abilist" to "arm64-v8a,armeabi-v7a,armeabi",
            "ro.soc.model" to "Tensor G6",
            "ro.serialno" to "1A2B3C4D",
            "ro.bootloader" to "unknown",
            "persist.sys.locale" to "ja-JP",
            "persist.sys.timezone" to "Asia/Tokyo"
        )
        val written = LSPosedConfig.parseDeviceProps(
            build(spec(props = full, model = "GM45K"))["system.prop"]
        )

        assertEquals(
            linkedMapOf(
                "ro.product.model" to "GM45K",
                "ro.product.odm.model" to "GM45K",
                "ro.product.system.model" to "GM45K",
                "ro.product.vendor.model" to "GM45K",
                "persist.sys.pixelprops.game" to "true"
            ),
            written
        )
        assertEquals(MagiskModuleBuilder.MODEL_KEYS, written.keys.toList().dropLast(1))
    }

    /**
     * Every boot-risky key a full profile renders. Named individually because the failure they cause
     * is not a wrong reading — it is a boot loop, recoverable only by deleting the module from
     * recovery, on a device this suite cannot reach.
     */
    @Test
    fun hardwareIdentityNeverReachesSystemProp() {
        val full = linkedMapOf(
            "ro.product.model" to "GM45K",
            "ro.hardware" to "google",
            "ro.board.platform" to "gs401",
            "ro.product.cpu.abi" to "arm64-v8a",
            "ro.product.cpu.abilist64" to "arm64-v8a",
            "ro.build.fingerprint" to "google/lynx/lynx:17/UP1A.260805.001/1:user/release-keys",
            "ro.serialno" to "1A2B3C4D",
            "ro.bootloader" to "unknown",
            "ro.soc.manufacturer" to "Google",
            "persist.sys.locale" to "ja-JP",
            "persist.sys.timezone" to "Asia/Tokyo"
        )
        val systemProp = build(spec(props = full, model = "GM45K")).getValue("system.prop")
        val flashed = LSPosedConfig.parseDeviceProps(systemProp).keys

        for (risky in full.keys - "ro.product.model") {
            assertFalse("$risky must not be flashed", flashed.contains(risky))
        }
    }

    /**
     * The counterpart to the allowlist. Dropping keys silently would make a five-property module read
     * as a broken one, so what is left out is listed in the file and counted in the installer — the
     * same reason the Safe Mode caveat ships inside `system.prop`.
     */
    @Test
    fun omittedKeysAreListedNotSilentlyDropped() {
        val spec = spec(
            props = linkedMapOf(
                "ro.product.model" to "GM45K",
                "ro.build.fingerprint" to "google/lynx/lynx:17/UP1A.260805.001/1:user/release-keys",
                "persist.sys.locale" to "ja-JP"
            ),
            model = "GM45K"
        )
        val members = build(spec)

        assertEquals(
            listOf("ro.build.fingerprint", "persist.sys.locale"),
            MagiskModuleBuilder.omittedKeys(spec)
        )
        // Listed as comments, so the omission is auditable from the flashed file itself.
        assertTrue(members.getValue("system.prop").contains("#   ro.build.fingerprint"))
        assertTrue(members.getValue("system.prop").contains("#   persist.sys.locale"))
        assertTrue(members.getValue("system.prop").contains("Held back for boot safety -- 2 profile keys"))
        // And stated while the user can still abort.
        assertTrue(members.getValue("customize.sh").contains("Held back:   2 profile keys not flashed."))
    }

    @Test
    fun aProfileWithNothingHeldBackWarnsAboutNothing() {
        val members = build(spec(props = linkedMapOf("ro.product.model" to "GM45K"), model = "GM45K"))
        assertFalse(members.getValue("system.prop").contains("Held back"))
        assertFalse(members.getValue("customize.sh").contains("Held back"))
    }

    // ------------------------------------------------------------------ Pixel flag

    /**
     * A Pixel preset's `ro.product.model` is a code (`GM45K`), not the word "Pixel", so keying the
     * flag off the model alone would read every Pixel profile as non-Pixel and flash `false` —
     * telling a PixelProps module to stop doing the exact thing the profile asked for.
     */
    @Test
    fun pixelFlagFollowsTheBrandNotTheModelString() {
        val pixel = spec(
            props = linkedMapOf("ro.product.brand" to "Google", "ro.product.model" to "GM45K"),
            model = "GM45K"
        )
        assertTrue(MagiskModuleBuilder.isPixelTarget(pixel))
        assertEquals(
            "true",
            LSPosedConfig.parseDeviceProps(build(pixel)["system.prop"])["persist.sys.pixelprops.game"]
        )
    }

    /**
     * The other half, and the reason the key is written at all rather than only when true: a profile
     * presenting a Galaxy cannot also be a Pixel, and leaving a PixelProps module's game spoof on
     * beside it produces a half-Pixel identity — a stronger signal than no spoof.
     */
    @Test
    fun aNonPixelPresetTurnsThePixelFlagOff() {
        val galaxy = spec(
            props = linkedMapOf(
                "ro.product.brand" to "samsung",
                "ro.product.manufacturer" to "samsung",
                "ro.product.model" to "SM-S948B"
            ),
            model = "SM-S948B"
        )
        assertFalse(MagiskModuleBuilder.isPixelTarget(galaxy))
        val written = LSPosedConfig.parseDeviceProps(build(galaxy)["system.prop"])
        assertEquals("false", written["persist.sys.pixelprops.game"])
        assertEquals("SM-S948B", written["ro.product.vendor.model"])
    }

    /** A hand-written profile that set a marketing name and left brand empty still resolves. */
    @Test
    fun pixelFlagFallsBackToTheModelPrefix() {
        val byName = spec(props = linkedMapOf("ro.product.model" to "Pixel 8 Pro"), model = "Pixel 8 Pro")
        assertTrue(MagiskModuleBuilder.isPixelTarget(byName))
    }

    /**
     * No model means this channel has nothing to deliver. Reported by the caller instead of shipping
     * a ZIP that installs cleanly and changes nothing — the failure the generated skeleton exists to
     * prevent.
     */
    @Test
    fun aProfileWithNoModelFlashesNothing() {
        val empty = spec(props = linkedMapOf("ro.build.id" to "UP1A.260805.001"), model = "")
        assertTrue(MagiskModuleBuilder.bootSafeProperties(empty).isEmpty())
    }

    /** The model survives when only [ModuleSpec.spoofedModel] carries it. */
    @Test
    fun theSpoofedModelIsTheFallbackSourceOfTheModel() {
        val noProp = spec(props = linkedMapOf("ro.build.id" to "UP1A.260805.001"), model = "SM-S948B")
        assertEquals(
            List(MagiskModuleBuilder.MODEL_KEYS.size) { "SM-S948B" },
            MagiskModuleBuilder.MODEL_KEYS.map { MagiskModuleBuilder.bootSafeProperties(noProp)[it] }
        )
    }

    // ------------------------------------------------------------------ module.prop

    @Test
    fun modulePropReportsTheRealAppVersion() {
        val props = LSPosedConfig.parseDeviceProps(build(spec())["module.prop"])
        assertEquals("2.0.0", props["version"])
        assertEquals("6", props["versionCode"])
        // Identity is deliberately fixed: Magisk keys installed modules by id, so a change here
        // installs a second module alongside the user's existing one.
        assertEquals(MagiskModuleBuilder.MODULE_ID, props["id"])
        assertEquals(MagiskModuleBuilder.MODULE_NAME, props["name"])
    }

    @Test
    fun modulePropStaysOneLinePerKey() {
        // A profile named across two lines would otherwise inject a bogus module.prop key.
        val members = build(spec(profileName = "Broken\nName", model = "Model\r\nInjected"))
        val moduleProp = members.getValue("module.prop")
        assertEquals(
            "every module.prop line is a key=value",
            moduleProp.lineSequence().count { it.isNotBlank() },
            moduleProp.lineSequence().count { it.isNotBlank() && it.contains('=') }
        )
    }

    // ------------------------------------------------------------------ system.prop

    @Test
    fun systemPropIsParseableAndOrderedModelFirst() {
        val given = linkedMapOf(
            "ro.product.brand" to "google",
            "ro.product.model" to "Pixel 8 Pro",
            "ro.build.id" to "AP31.240617.009"
        )
        val written = LSPosedConfig.parseDeviceProps(build(spec(props = given))["system.prop"])
        // Comment lines carrying held-back keys must not read back as properties.
        assertEquals(MagiskModuleBuilder.MODEL_KEYS.size + 1, written.size)
        assertEquals("ro.product.model", written.keys.first())
        assertEquals(MagiskModuleBuilder.KEY_PIXELPROPS_GAME, written.keys.last())
    }

    @Test
    fun systemPropSaysItIsDeviceWide() {
        // A flashed module long outlives the screen that generated it, so the caveat ships with it.
        val header = build(spec()).getValue("system.prop")
        assertTrue(header.contains("device-wide"))
        assertTrue(header.contains("Safe Mode"))
    }

    /**
     * The bug this whole change exists for: a rendered profile also carries keys only this app's
     * hooks understand, and handing those to `resetprop` publishes names like `device.imei` where
     * any app's `getprop` can read them — a stronger detection signal than not spoofing at all.
     */
    @Test
    fun appOwnKeysNeverReachSystemProp() {
        val rendered = """
            # Catsmoker generated profile

            ro.product.model=Pixel 8 Pro
            ro.build.id=AP31.240617.009
            screen.width=1440
            screen.height=3120
            device.imei=353626079109085
            device.gaid=cafe-1234
            ANDROID_ID=9f0e1d2c3b4a5968
            webview.user_agent=Mozilla/5.0

            # Global Settings
            safe_mode.packages=com.tencent.ig
            device.apply_screen_metrics=1
        """.trimIndent()

        val filtered = LSPosedConfig.filterToSystemProperties(rendered)
        val systemProp = build(spec(props = filtered)).getValue("system.prop")

        assertEquals(
            linkedMapOf("ro.product.model" to "Pixel 8 Pro", "ro.build.id" to "AP31.240617.009"),
            filtered
        )
        for (leak in listOf(
            "screen.width", "screen.height", "device.imei", "device.gaid",
            "ANDROID_ID", "webview.user_agent", "safe_mode.packages", "device.apply_screen_metrics"
        )) {
            assertFalse("$leak leaked into system.prop", systemProp.contains(leak))
        }
    }
}
