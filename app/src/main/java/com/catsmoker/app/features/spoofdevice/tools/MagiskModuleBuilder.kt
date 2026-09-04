package com.catsmoker.app.features.spoofdevice.tools

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the flashable Magisk module for a spoof profile, entirely in code.
 *
 * This used to be a copy of `assets/magisk/`: [ZipOutputStream] walked the asset tree and swapped
 * one member's bytes on the way past. Two things were wrong with that, and both are why the
 * skeleton is generated here now.
 *
 * The first was silent coupling. The exported ZIP only ever contained what the asset walk happened
 * to enumerate, so `system.prop` — the one member carrying the spoof — existed in the archive only
 * because a placeholder file with a hardcoded `OPD2415` model sat in `assets/` to be overwritten.
 * Deleting what looked like dead placeholder content would have shipped a module that installs
 * cleanly and spoofs nothing.
 *
 * The second was `module.prop` going stale. It was checked in with `version=1.0.0 versionCode=5`
 * and stayed there while the app moved to 2.0.0 / 6, so every generated module misreported itself
 * in the Magisk manager. Both fields now come from the caller's real build values.
 *
 * What this deliberately does *not* do is carry the profile. It flashes [MODEL_KEYS] and
 * [KEY_PIXELPROPS_GAME] and nothing else, however much the caller hands it — see [MODEL_KEYS] for
 * why a narrow allowlist is the safe shape for this one channel, and [omittedKeys] for how the rest
 * of the profile is reported rather than silently dropped.
 *
 * ## Cross-checked against `referance/Magisk-Modules`
 *
 * Three shipped FPS/spoof modules sit in the workspace, and the members here answer a gap each one
 * of them had already closed:
 *
 * - `Unlocker-p4/customize.sh` prints `getprop ro.product.model` — the device's *real* model — so a
 *   flashing user sees what is being replaced. Ours printed only the spoof, which is the half you
 *   already know. Its README is also the one that names `MagiskHidePropsConf` as incompatible; the
 *   installer now looks for that overlap instead of leaving it in prose nobody re-reads.
 * - `HunterX-Reborn-II/common/service.sh` waits for the boot to settle (`vendor.post_boot.parsed`)
 *   before it trusts anything it reads. [serviceSh] is the same idea turned to this module's
 *   purpose: read the spoof back and report what the device actually did.
 * - `No-Fps-Cap` is the counter-example — its whole payload is three Samsung `privapp-permissions`
 *   XMLs and its own description says "Exynos only". Nothing in it generalises, which is why no
 *   `system/` overlay is generated here.
 *
 * [updateBinary]'s KernelSU/APatch branch was cross-checked against a fourth,
 * `FPS-Limitations-Patcher-v3.1`, which carried `/data/adb/ksu/bin` and `/data/adb/ap/bin` on
 * `PATH` and shared `KSU`/`KSU_VER_CODE` with its installer. **That module is no longer in
 * `referance/`** — it was removed mid-2026-08 and the folder is not under version control, so the
 * citation is unverifiable now. The branch stands on its own: it only tests two paths for existence
 * and sources neither, so nothing about it depends on a layout that tree would have confirmed.
 */
object MagiskModuleBuilder {

    /**
     * Module identity in the Magisk manager.
     *
     * Held constant on purpose: Magisk keys an installed module by `id`, so changing this would
     * make the next generated ZIP install *alongside* a user's existing module instead of replacing
     * it, leaving two modules writing the same properties.
     */
    const val MODULE_ID = "fpsunlocker"
    const val MODULE_NAME = "Mobile FPS Unlocker"
    const val MODULE_AUTHOR = "catsmoker"

    /** Matches the floor `update-binary` enforces via `MAGISK_VER_CODE`. */
    const val MIN_MAGISK_VER_CODE = 20400

    /** Where [serviceSh] leaves its per-boot reading, beside the module it describes. */
    const val VERIFY_LOG_NAME = "verify.log"

    /**
     * The only device-identity keys a generated `system.prop` carries — and the whole of the spoof
     * this channel delivers.
     *
     * ## Why this is a short allowlist and not a filter
     *
     * This channel used to flash whatever passed
     * [com.catsmoker.app.shared.data.model.LSPosedConfig.SYSTEM_PROPERTY_PREFIXES], which for a full
     * profile is forty-odd properties: every partition twin of brand/manufacturer/name/device, five
     * build fingerprints, `ro.build.id`/`.type`/`.tags`/`.description`, `ro.hardware`,
     * `ro.board.platform`, `ro.product.cpu.abi*`, `ro.soc.*`, `ro.serialno`, `ro.bootloader`,
     * `persist.sys.locale`, `persist.sys.timezone`.
     *
     * That set is safe **in-process**, where a hook answers one app and the real device is untouched.
     * It is not safe here. `system.prop` is consumed by `resetprop` at post-fs-data — before the
     * framework starts — and rewrites those keys for *everything* on the device: vendor HALs, init
     * `on property` triggers, the ABI list the runtime picks its libraries from. A `ro.hardware`,
     * `ro.board.platform` or `ro.product.cpu.abi` that disagrees with the actual silicon is not a
     * detection risk, it is a device that does not finish booting, and the fix is deleting the module
     * from recovery. `ro.build.fingerprint` and `ro.serialno` are read by vendor services with no
     * obligation to tolerate a value that never shipped.
     *
     * None of it was buying anything, either. A game's frame-rate table is keyed on the model, so the
     * model *is* the payload — which is also all `referance/Magisk-Modules/Unlocker-p4` writes, and
     * the counter-example `HunterX-Reborn-II` is the one that flashed a pile of extra keys.
     *
     * So this channel's rule is the inverse of the app's usual completeness instinct: **fewer
     * properties is strictly better.** A key added here trades boot-loop risk for nothing unless
     * something is known to read it. The goal is not to impersonate a phone; it is to hand the game
     * the minimum model identity its whitelist looks up, leaving the real Android environment intact.
     *
     * The in-process channels are unchanged and still carry the whole profile — they cannot cost a
     * boot — so this narrows one delivery path, not what the app spoofs.
     *
     * The four keys are the model and the partition twins an app can reach with `getprop`. Android
     * resolves `Build.MODEL` from `ro.product.model` alone, so the twins are here for agreement
     * rather than for effect: a `getprop ro.product.vendor.model` still reading the real phone beside
     * a spoofed `ro.product.model` is the kind of disagreement
     * [com.catsmoker.app.features.spoofdevice.root.GetPropInterceptor] exists to prevent.
     */
    val MODEL_KEYS: List<String> = listOf(
        "ro.product.model",
        "ro.product.odm.model",
        "ro.product.system.model",
        "ro.product.vendor.model"
    )

    /**
     * Switch read by the PixelProps family of Magisk modules to decide whether *they* should present
     * this device as a Pixel to games.
     *
     * Not a framework property: nothing in Android reads it, and on a device with no such module
     * installed it is inert — "no effect" rather than "failed". It is set because where one *is*
     * installed, its game spoof and this module's model both reach the same games, `resetprop` is
     * last-one-wins, and the loser is silent. Deriving it from the selected preset makes the two
     * agree instead of fighting:
     *
     * - Pixel preset → `true`, so its Pixel work runs alongside ours.
     * - anything else → `false`, because a profile presenting a Galaxy or a Xiaomi cannot also be a
     *   Pixel, and a half-Pixel identity is a stronger signal than no spoof at all.
     *
     * It is the one non-model key flashed, so the installer names it and its value explicitly.
     */
    const val KEY_PIXELPROPS_GAME = "persist.sys.pixelprops.game"

    /**
     * @param systemProperties the profile's full rendered system properties. Only [MODEL_KEYS] and
     *   [KEY_PIXELPROPS_GAME] are flashed from it; the rest is reported by [omittedKeys] and left to
     *   the in-process channels. Passing more cannot make this module write more.
     * @param versionName mirrored into `module.prop` `version`; pass `BuildConfig.VERSION_NAME`.
     * @param versionCode mirrored into `module.prop` `versionCode`; pass `BuildConfig.VERSION_CODE`.
     * @param profileName shown by the installer so a user flashing a months-old ZIP can tell which
     *   profile it holds.
     * @param spoofedModel the profile's `ro.product.model`, used in the installer banner and the
     *   module description — and as the fallback source of the model itself when [systemProperties]
     *   carries no `ro.product.model`.
     */
    data class ModuleSpec(
        val systemProperties: Map<String, String>,
        val versionName: String,
        val versionCode: Int,
        val profileName: String,
        val spoofedModel: String
    )

    /**
     * Writes the complete module to [out]. The caller owns closing [out].
     *
     * @return the ZIP entry paths written, in order — the honest record of what the archive holds,
     *   rather than an assumption that a fixed set of files went in.
     */
    fun write(out: OutputStream, spec: ModuleSpec): List<String> {
        val entries = linkedMapOf(
            "META-INF/com/google/android/update-binary" to updateBinary(),
            "META-INF/com/google/android/updater-script" to UPDATER_SCRIPT,
            "module.prop" to moduleProp(spec),
            "system.prop" to systemProp(spec),
            "customize.sh" to customizeSh(spec),
            "service.sh" to serviceSh(spec)
        )
        // Not closed here: closing a ZipOutputStream closes the stream under it, and on the
        // MediaStore path that stream belongs to the caller's `use` block.
        val zip = ZipOutputStream(out)
        for ((path, body) in entries) {
            zip.putNextEntry(ZipEntry(path))
            zip.write(body.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        zip.finish()
        return entries.keys.toList()
    }

    /**
     * Exactly what the generated `system.prop` will contain: [MODEL_KEYS] set to the profile's model,
     * then [KEY_PIXELPROPS_GAME].
     *
     * Empty when the profile has no model at all — there is nothing for this channel to deliver, and
     * a ZIP that installs cleanly and changes nothing is the failure this generator was written to
     * end. The caller reports that instead of shipping it.
     */
    fun bootSafeProperties(spec: ModuleSpec): Map<String, String> {
        val model = modelOf(spec)
        if (model.isBlank()) return emptyMap()
        val props = linkedMapOf<String, String>()
        for (key in MODEL_KEYS) props[key] = model
        props[KEY_PIXELPROPS_GAME] = isPixelTarget(spec).toString()
        return props
    }

    /**
     * The profile's own keys this channel deliberately does not flash, in render order.
     *
     * Named, not silently discarded. A user who set a locale, a fingerprint or an SoC in the profile
     * is entitled to know the flashed module carries none of them and that the in-process channels
     * still do — the same reason the Safe Mode caveat is stated in the file rather than assumed. This
     * is what the installer counts and what `system.prop` lists in its header.
     */
    fun omittedKeys(spec: ModuleSpec): List<String> {
        val flashed = bootSafeProperties(spec).keys
        return spec.systemProperties.keys.filterNot { it in flashed }
    }

    /**
     * Whether the selected preset is a Google Pixel, which is what [KEY_PIXELPROPS_GAME] turns on.
     *
     * Brand and manufacturer are checked first because a Pixel's `ro.product.model` is a code
     * (`GM45K`), not the word "Pixel" — keying off the model alone would read every Pixel preset as
     * non-Pixel and flash `false`. The model prefix is the fallback for a hand-written profile that
     * set a marketing name and left brand empty.
     */
    fun isPixelTarget(spec: ModuleSpec): Boolean {
        val identity = listOf("ro.product.brand", "ro.product.manufacturer")
            .mapNotNull { spec.systemProperties[it]?.trim() }
        if (identity.any { it.equals("google", ignoreCase = true) }) return true
        return modelOf(spec).startsWith("Pixel", ignoreCase = true)
    }

    /** The model to flash: the rendered property, or [ModuleSpec.spoofedModel] when it is absent. */
    private fun modelOf(spec: ModuleSpec): String = singleLine(
        spec.systemProperties["ro.product.model"]?.takeIf { it.isNotBlank() } ?: spec.spoofedModel
    )

    // ------------------------------------------------------------------ members

    /**
     * `system.prop`, which Magisk feeds to `resetprop` at post-fs-data.
     *
     * Carries [bootSafeProperties] only — see [MODEL_KEYS] for why this file stays as short as it
     * does. Two facts ship inside it rather than in the screen that generated it, because a flashed
     * module outlives that screen by months: it is device-global, so `safe_mode.packages` cannot be
     * honoured here even when the profile carries one; and every profile key left out is listed, so
     * the omission is auditable from the file itself.
     */
    private fun systemProp(spec: ModuleSpec): String = buildString {
        val flashed = bootSafeProperties(spec)
        val omitted = omittedKeys(spec)
        append("# CatSmoker spoof profile: ").append(singleLine(spec.profileName)).append('\n')
        append("# Generated by CatSmoker ").append(singleLine(spec.versionName)).append('\n')
        append("#\n")
        append("# Model identity only, on purpose. resetprop applies this file to the whole\n")
        append("# device before the framework starts, so a key that disagrees with the real\n")
        append("# silicon -- ro.hardware, ro.board.platform, ro.product.cpu.abi -- costs a boot,\n")
        append("# not just a detection. A game's FPS table reads the model, so the model is all\n")
        append("# this needs to carry. Fewer keys here is strictly safer.\n")
        append("#\n")
        append("# Applies device-wide. Per-app Safe Mode exclusions cannot apply to a\n")
        append("# system.prop and are not present in this file.\n")
        if (omitted.isNotEmpty()) {
            append("#\n")
            append("# Held back for boot safety -- ").append(omitted.size)
            append(if (omitted.size == 1) " profile key. The" else " profile keys. The")
            append(" in-process hooks\n")
            append("# (LSPosed module, config provider) still apply these per-app; this file\n")
            append("# deliberately does not:\n")
            for (key in omitted) append("#   ").append(key).append('\n')
        }
        append('\n')
        for ((key, value) in flashed) {
            append(key).append('=').append(value).append('\n')
        }
    }

    /**
     * The identity Magisk keys the module by, shared with [serviceSh].
     *
     * [serviceSh] rewrites `module.prop` after every boot to report what applied, so it has to
     * reproduce these five lines exactly. Generating both from here is what keeps a bumped
     * `versionCode` from silently reverting to a stale one on the first reboot.
     */
    private fun identityLines(spec: ModuleSpec): List<String> = listOf(
        "id=$MODULE_ID",
        "name=$MODULE_NAME",
        "version=${singleLine(spec.versionName)}",
        "versionCode=${spec.versionCode}",
        "author=$MODULE_AUTHOR"
    )

    private fun moduleProp(spec: ModuleSpec): String =
        lf(identityLines(spec) + "description=${installDescription(spec)}" + "")

    /**
     * What the module list says between flashing and the first reboot: a request, not a result.
     *
     * Single line, and never empty — Magisk shows `description` verbatim.
     */
    private fun installDescription(spec: ModuleSpec): String = buildString {
        append("Spoofs this device's model as ")
        append(singleLine(spec.spoofedModel).ifBlank { "the selected profile" })
        append(" (")
        append(bootSafeProperties(spec).size)
        append(" properties, model identity only) to unlock higher FPS and graphics tiers in mobile ")
        append("games. Reboot, then this line reports how many actually applied.")
    }

    /**
     * The installer banner. Sourced by `install_module` when present, so it is the only place the
     * flashing user sees which profile they are about to apply.
     *
     * Three things it now does beyond printing:
     *
     * - Prints the device's real `ro.product.model` beside the spoofed one, as
     *   `Unlocker-p4/customize.sh` does. The value being replaced is the half of the swap the
     *   banner was missing.
     * - Names any other enabled module writing the same property keys. `resetprop` is
     *   last-one-wins at post-fs-data and the loser is silent, so a second model-spoofing module
     *   presents as "the spoof stopped working" with nothing to point at. The scan compares actual
     *   key sets rather than a curated list of module names, so it does not go stale — with one
     *   hand-written exception, `MagiskHidePropsConf`, which sets its properties from
     *   `post-fs-data.sh` where a `system.prop` scan cannot see them. That is the module
     *   `Unlocker-p4`'s README warns about by name, for exactly this reason.
     * - States what is *not* being flashed. [omittedKeys] is the honest counterpart to a deliberately
     *   short [MODEL_KEYS]: a user who set a fingerprint or a locale in the profile would otherwise
     *   read a five-property module as a broken one. It also prints [KEY_PIXELPROPS_GAME] and its
     *   value, since that is the one key here that is not the model.
     *
     * Nothing here calls `exit`: the script is *sourced* by `install_module`, so an `exit` would
     * take the whole installer down. Every branch is a guard.
     */
    private fun customizeSh(spec: ModuleSpec): String = lf(
        buildList {
            val flashed = bootSafeProperties(spec)
            val omitted = omittedKeys(spec)
            add("#!/sbin/sh")
            add("")
            add("ui_print \"---------------------------------\"")
            add("ui_print \"          FPS Unlocker           \"")
            add("ui_print \"       Made by catsmoker         \"")
            add("ui_print \"---------------------------------\"")
            add("ui_print \"Profile:     ${shellSafe(spec.profileName)}\"")
            add("ui_print \"Spoofed as:  ${shellSafe(spec.spoofedModel)}\"")
            // The value being replaced, straight from the device, the way Unlocker-p4 prints it.
            add("ui_print \"This device: \$(getprop ro.product.model)\"")
            add("ui_print \"Flashing:    ${flashed.size} properties (model identity only)\"")
            add("ui_print \"Pixel flag:  $KEY_PIXELPROPS_GAME=${isPixelTarget(spec)}\"")
            add("ui_print \"---------------------------------\"")
            add("")

            if (omitted.isNotEmpty()) {
                add("# Stated while the user can still abort, and while it can still be explained")
                add("# as a choice rather than discovered as a spoof that only half applied.")
                add("ui_print \"Held back:   ${omitted.size} profile keys not flashed.\"")
                add("ui_print \"  A device-wide build, SoC or ABI key that\"")
                add("ui_print \"  disagrees with the real hardware costs a\"")
                add("ui_print \"  boot, and no FPS table reads one. The\"")
                add("ui_print \"  in-process hooks still apply them per-app.\"")
                add("ui_print \"---------------------------------\"")
                add("")
            }

            add("# Report any other module writing the same keys. resetprop is last-one-wins at")
            add("# post-fs-data and the loser says nothing, so the overlap is named here instead")
            add("# of being discovered as a spoof that quietly stopped working.")
            add("MODID=\"\${MODID:-$MODULE_ID}\"")
            add("MODULES=\"\${NVBASE:-/data/adb}/modules\"")
            add("OURS=\"\$MODPATH/.catsmoker_keys\"")
            add("CONFLICTS=\"\"")
            add("if [ -f \"\$MODPATH/system.prop\" ]; then")
            add("  sed -n 's/^[[:space:]]*\\([a-zA-Z0-9._-]*\\)=.*/\\1/p' \"\$MODPATH/system.prop\" | sort -u > \"\$OURS\"")
            add("  for OTHER in \"\$MODULES\"/*; do")
            add("    [ -d \"\$OTHER\" ] || continue")
            add("    OTHERID=\"\${OTHER##*/}\"")
            add("    [ \"\$OTHERID\" = \"\$MODID\" ] && continue")
            add("    [ -f \"\$OTHER/disable\" ] && continue")
            add("    [ -f \"\$OTHER/system.prop\" ] || continue")
            add("    SHARED=\$(sed -n 's/^[[:space:]]*\\([a-zA-Z0-9._-]*\\)=.*/\\1/p' \"\$OTHER/system.prop\" | sort -u | grep -Fxf \"\$OURS\" | wc -l)")
            add("    [ \"\$SHARED\" -gt 0 ] 2>/dev/null && CONFLICTS=\"\$CONFLICTS \$OTHERID(\$SHARED)\"")
            add("  done")
            add("  rm -f \"\$OURS\"")
            add("fi")
            add("# MagiskHidePropsConf sets its properties from post-fs-data.sh, where the scan")
            add("# above cannot see them. It is the module Unlocker's README names by hand.")
            add("if [ -d \"\$MODULES/MagiskHidePropsConf\" ] && [ ! -f \"\$MODULES/MagiskHidePropsConf/disable\" ]; then")
            add("  CONFLICTS=\"\$CONFLICTS MagiskHidePropsConf\"")
            add("fi")
            add("if [ -n \"\$CONFLICTS\" ]; then")
            add("  ui_print \"! Also setting device properties:\"")
            add("  ui_print \"!  \$CONFLICTS\"")
            add("  ui_print \"! Whichever loads last wins. Disable the others if\"")
            add("  ui_print \"! this profile does not take effect.\"")
            add("  ui_print \"---------------------------------\"")
            add("fi")
            add("")
            add("ui_print \"Applies device-wide. Reboot to apply.\"")
            add("ui_print \"Then this module's description and\"")
            add("ui_print \"$VERIFY_LOG_NAME report what the device did.\"")
            add("ui_print \"          Happy gaming!          \"")
            add("ui_print \"---------------------------------\"")
            add("")
        }
    )

    /**
     * Post-boot verification, run by Magisk at `late_start`.
     *
     * The strongest convention in this app is to read a value back and report the measured fact
     * rather than the request — `ExecResult`, `GamingModeReport`, `MetricReadStatus` all exist for
     * that. A flashed module was the one channel with no such report: it either worked or it
     * didn't, and the user had no way to tell which properties `resetprop` had actually landed.
     * It genuinely varies, because vendor init keeps writing properties long after post-fs-data.
     *
     * A module has exactly two surfaces to report on, and this writes both: the `description`
     * Magisk shows in its module list, and [VERIFY_LOG_NAME] beside itself.
     *
     * Three details are deliberate:
     *
     * - It waits for `sys.boot_completed`, the way `HunterX-Reborn-II/common/service.sh` waits for
     *   `vendor.post_boot.parsed`, because a reading taken at `late_start` would call a property
     *   applied that is about to be overwritten. The wait is bounded, and a device that never
     *   reports it gets that recorded in the log rather than silently assumed away.
     * - "not set" and "overwritten" stay distinct — an absent property means `resetprop` never
     *   created it, a differing one means something on the device won. Those need different fixes,
     *   which is the same reason `GamingModeReport` uses nullable fields.
     * - The log is truncated, never appended: it describes the boot the user is in.
     */
    private fun serviceSh(spec: ModuleSpec): String = lf(
        buildList {
            add("#!/sbin/sh")
            add("")
            add("MODDIR=\${0%/*}")
            add("PROP=\"\$MODDIR/system.prop\"")
            add("LOG=\"\$MODDIR/$VERIFY_LOG_NAME\"")
            add("")
            add("[ -f \"\$PROP\" ] || exit 0")
            add("")
            add("# Vendor init writes properties well past post-fs-data, so a reading taken now")
            add("# would report a spoof as applied that is about to be overwritten. Bounded, so a")
            add("# device that never reports boot_completed cannot leave this looping forever --")
            add("# and the timeout is recorded below rather than assumed away.")
            add("WAITED=0")
            add("BOOTED=1")
            add("while [ \"\$(getprop sys.boot_completed)\" != \"1\" ]; do")
            add("  if [ \"\$WAITED\" -ge 120 ]; then")
            add("    BOOTED=0")
            add("    break")
            add("  fi")
            add("  WAITED=\$((WAITED + 2))")
            add("  sleep 2")
            add("done")
            add("sleep 5")
            add("")
            add("TOTAL=0")
            add("OK=0")
            add("MISSING=0")
            add("CHANGED=0")
            add("")
            add("echo \"# CatSmoker spoof verification\" > \"\$LOG\"")
            add("echo \"# profile: ${shellSafe(spec.profileName)}\" >> \"\$LOG\"")
            add("echo \"# spoofed as: ${shellSafe(spec.spoofedModel)}\" >> \"\$LOG\"")
            add("echo \"# module: $MODULE_ID ${singleLine(spec.versionName)} (${spec.versionCode})\" >> \"\$LOG\"")
            add("echo \"# read back: \$(date 2>/dev/null)\" >> \"\$LOG\"")
            add("if [ \"\$BOOTED\" != \"1\" ]; then")
            add("  echo \"# sys.boot_completed never reported 1 within 120s - readings may be early\" >> \"\$LOG\"")
            add("fi")
            add("echo \"\" >> \"\$LOG\"")
            add("")
            add("# Redirected from a file, not piped: piping into this loop would run it in a")
            add("# subshell and every counter below would come back zero.")
            add("while IFS= read -r LINE; do")
            add("  case \"\$LINE\" in")
            add("    ''|'#'*) continue ;;")
            add("  esac")
            add("  KEY=\${LINE%%=*}")
            add("  WANT=\${LINE#*=}")
            add("  [ \"\$KEY\" = \"\$LINE\" ] && continue")
            add("  TOTAL=\$((TOTAL + 1))")
            add("  GOT=\$(getprop \"\$KEY\")")
            add("  if [ \"\$GOT\" = \"\$WANT\" ]; then")
            add("    OK=\$((OK + 1))")
            add("  elif [ -z \"\$GOT\" ]; then")
            add("    # resetprop never created it. Distinct from a value something else replaced.")
            add("    MISSING=\$((MISSING + 1))")
            add("    echo \"not set:     \$KEY (wanted '\$WANT')\" >> \"\$LOG\"")
            add("  else")
            add("    CHANGED=\$((CHANGED + 1))")
            add("    echo \"overwritten: \$KEY is '\$GOT' (wanted '\$WANT')\" >> \"\$LOG\"")
            add("  fi")
            add("done < \"\$PROP\"")
            add("")
            add("echo \"\" >> \"\$LOG\"")
            add("echo \"applied \$OK/\$TOTAL, \$MISSING not set, \$CHANGED overwritten after boot\" >> \"\$LOG\"")
            add("")
            add("# The module list is the only report a user sees without a file manager.")
            add("DESC=\"Applied \$OK/\$TOTAL properties\"")
            add("if [ \"\$MISSING\" -gt 0 ]; then")
            add("  DESC=\"\$DESC, \$MISSING not set\"")
            add("fi")
            add("if [ \"\$CHANGED\" -gt 0 ]; then")
            add("  DESC=\"\$DESC, \$CHANGED overwritten by the device\"")
            add("fi")
            add("DESC=\"\$DESC - spoofing as ${shellSafe(spec.spoofedModel)}. Details in $VERIFY_LOG_NAME.\"")
            add("")
            add("# Rewritten whole rather than sed'd in place: property values carry slashes and")
            add("# ampersands that a sed replacement would eat.")
            add("{")
            add("  cat <<'$MODULE_PROP_HEREDOC'")
            addAll(identityLines(spec))
            add(MODULE_PROP_HEREDOC)
            add("  echo \"description=\$DESC\"")
            add("} > \"\$MODDIR/module.prop.new\" && mv -f \"\$MODDIR/module.prop.new\" \"\$MODDIR/module.prop\"")
            add("")
        }
    )

    /**
     * The stock Magisk installer entry point, reproduced from the script this app shipped as an
     * asset. The Magisk path through it is left untouched — it is the known-good template, and a
     * clever rewrite of it would only be a new way to fail on someone's device.
     *
     * What changed is the failure message. Magisk, KernelSU and APatch all install this module
     * format, and this app's own UI offers to open whichever of the three is present
     * ([com.catsmoker.app.features.spoofdevice.SpoofDeviceViewModel.launchRootManager]), but only
     * Magisk ships `/data/adb/magisk/util_functions.sh`. KernelSU and APatch install modules from
     * their own manager and never source this script, so a user who reached it under either one saw
     * "Please install Magisk v20.4+" — false, and no help at all. They now get told where the
     * installer actually lives.
     *
     * No path under `/data/adb/ksu` or `/data/adb/ap` is *sourced* on the strength of that — only
     * tested for existence, so the branch cannot depend on a layout this repo has not verified.
     */
    private fun updateBinary(): String = lf(
        "#!/sbin/sh",
        "",
        "#################",
        "# Initialization",
        "#################",
        "",
        "umask 022",
        "",
        "# Print message to the console",
        "ui_print() {",
        "  echo \"\$1\"",
        "}",
        "",
        "# Function to require a newer version of Magisk",
        "require_new_magisk() {",
        "  ui_print \"*******************************\"",
        "  ui_print \" Please install Magisk v20.4+! \"",
        "  ui_print \"*******************************\"",
        "  exit 1",
        "}",
        "",
        "# KernelSU and APatch install this module format from their own manager, which",
        "# never sources this script. Reaching here under one of them means the ZIP was",
        "# flashed somewhere that cannot install it -- so say where the installer is,",
        "# instead of blaming a missing Magisk.",
        "require_own_manager() {",
        "  ui_print \"*******************************\"",
        "  ui_print \" Install this from the \$1 app: \"",
        "  ui_print \" Modules -> Install from storage\"",
        "  ui_print \"*******************************\"",
        "  exit 1",
        "}",
        "",
        "#########################",
        "# Load util_functions.sh",
        "#########################",
        "",
        "OUTFD=\$2",
        "ZIPFILE=\$3",
        "",
        "# Ensure /data is mounted",
        "mount /data 2>/dev/null",
        "",
        "# Check for the presence of util_functions.sh and Magisk version",
        "if [ ! -f /data/adb/magisk/util_functions.sh ]; then",
        "  if [ \"\$KSU\" = \"true\" ] || [ -d /data/adb/ksu ]; then",
        "    require_own_manager \"KernelSU\"",
        "  fi",
        "  if [ \"\$APATCH\" = \"true\" ] || [ -d /data/adb/ap ]; then",
        "    require_own_manager \"APatch\"",
        "  fi",
        "  require_new_magisk",
        "fi",
        ". /data/adb/magisk/util_functions.sh",
        "if [ \$MAGISK_VER_CODE -lt $MIN_MAGISK_VER_CODE ]; then",
        "  require_new_magisk",
        "fi",
        "",
        "# Execute module installation",
        "install_module",
        "exit 0",
        ""
    )

    /** The marker Magisk uses to recognise a flashable module. */
    private const val UPDATER_SCRIPT = "#MAGISK\n"

    /** Delimiter for the literal `module.prop` block [serviceSh] reprints. */
    private const val MODULE_PROP_HEREDOC = "CATSMOKER_MODULE_PROP"

    // ------------------------------------------------------------------ helpers

    /**
     * Joins with an explicit `\n`.
     *
     * The scripts here are executed by `sh` on the device, where a CR makes the shebang a "bad
     * interpreter". Building them line-by-line rather than from a multiline literal keeps that
     * independent of how this source file happens to be stored or checked out — which is what the
     * repo's `.gitattributes` had to enforce while these lived in `assets/`.
     */
    private fun lf(lines: List<String>): String = lines.joinToString("\n")

    private fun lf(vararg lines: String): String = lf(lines.asList())

    /** Collapses anything a profile name could contain into one safe `key=value` line. */
    private fun singleLine(raw: String): String =
        raw.replace('\n', ' ').replace('\r', ' ').trim()

    /** Same, plus the characters that would break out of a `ui_print "…"` argument. */
    private fun shellSafe(raw: String): String =
        singleLine(raw).replace("\\", "").replace("\"", "").replace("$", "").replace("`", "")
}
