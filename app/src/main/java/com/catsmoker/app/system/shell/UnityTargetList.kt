package com.catsmoker.app.system.shell

import com.google.gson.JsonParser

/**
 * Shape-only port of the reference TargetList channel (M155).
 *
 * Read in full before writing:
 * `reference/spoofdevice/unity-fps-unlocker/TargetList.json` (global + custom
 * sample), `UnityFPSUnlocker/main.cc:31-86` (global-default + per-package
 * copy-override merge; empty object = global copy), `:88-92` (inotify
 * hot-reload), `:99-136` (companion loads-once + per-package dispatch),
 * `:223-236` (presence gate + fps:0/DLCLOSE unload), and
 * `utility/config.hh:15-33` (defaults: delay 5, fps 90, mod_opcode true,
 * scale -1).
 *
 * What this ports: the user-JSON shape, the merge rule, the presence flag, and
 * the explicit-off/unload rule — pure data, JVM-testable, no privilege. What
 * it deliberately does NOT port: file watching (inotify), companion/socket
 * dispatch, native calls, `mod_opcode` patching (M154 rejected — parsed for
 * compatibility, never acted upon), resolution scaling (M153 kept as the `wm`
 * size/density path, never opcode scanning), Houdini/x86 (M156 conditional).
 * An `fps` value here is a stored number only: only a measured panel-peak value
 * is ever admissible behaviorally (no-caps rule), and any behavioral consumer
 * stays blocked on ban-risk review + NDK consumer + profile-model approval
 * (TODO §2 item 13).
 */
object UnityTargetList {

    data class TargetConfig(
        val delaySec: Int = 5,
        val fps: Int = 90,
        val modOpcode: Boolean = true,
        val scale: Float = -1f
    )

    data class ParsedList(
        val global: TargetConfig = TargetConfig(),
        val custom: Map<String, TargetConfig> = emptyMap()
    )

    data class Resolved(
        val hasCustom: Boolean,
        val config: TargetConfig
    )

    /**
     * Parses a TargetList document. Never throws: malformed input reads back as
     * reference defaults + empty custom, never a fabricated entry. Wrong-typed
     * fields keep the global value (mirrors the reference `IsInt`/`IsBool`/
     * `IsFloat` guards); unknown fields (e.g. `description`) are ignored, so a
     * description-only object copies the global exactly like the reference's
     * empty-member path.
     */
    fun parse(json: String): ParsedList = runCatching {
        val root = JsonParser.parseString(json)
        if (!root.isJsonObject) return ParsedList()
        val rootObj = root.asJsonObject
        val global = readConfig(rootObj.get("global"), TargetConfig())
        val custom = mutableMapOf<String, TargetConfig>()
        val customEl = rootObj.get("custom")
        if (customEl != null && customEl.isJsonObject) {
            for ((pkg, entry) in customEl.asJsonObject.entrySet()) {
                if (!entry.isJsonObject) continue
                custom[pkg] = readConfig(entry, global)
            }
        }
        ParsedList(global, custom)
    }.getOrDefault(ParsedList())

    /** Per-package lookup: exact match owns the package, otherwise the global. */
    fun resolve(list: ParsedList, packageName: String): Resolved {
        val cfg = list.custom[packageName]
        return if (cfg != null) Resolved(hasCustom = true, config = cfg)
        else Resolved(hasCustom = false, config = list.global)
    }

    /**
     * Unload rule mirroring `postAppSpecialize`: stay loaded only when a reason
     * exists (custom entry or on-disk il2cpp marker) AND an explicit positive
     * fps is stored. `fps = 0` is the reference's explicit-off (DLCLOSE), not a
     * cap — a cap would be a positive ceiling below peak, which this never imposes.
     */
    fun shouldUnload(hasCustom: Boolean, hasIl2cppMarker: Boolean, fps: Int): Boolean =
        !((hasCustom || hasIl2cppMarker) && fps > 0)

    private fun readConfig(el: com.google.gson.JsonElement?, base: TargetConfig): TargetConfig {
        if (el == null || !el.isJsonObject) return base
        val obj = el.asJsonObject
        return base.copy(
            delaySec = readInt(obj.get("delay")) ?: base.delaySec,
            fps = readInt(obj.get("fps")) ?: base.fps,
            modOpcode = readBool(obj.get("mod_opcode")) ?: base.modOpcode,
            scale = readFloat(obj.get("scale")) ?: base.scale
        )
    }

    private fun readInt(el: com.google.gson.JsonElement?): Int? {
        if (el == null || !el.isJsonPrimitive) return null
        val p = el.asJsonPrimitive
        if (!p.isNumber) return null
        return runCatching { p.asInt }.getOrNull()
    }

    private fun readBool(el: com.google.gson.JsonElement?): Boolean? {
        if (el == null || !el.isJsonPrimitive) return null
        val p = el.asJsonPrimitive
        if (!p.isBoolean) return null
        return runCatching { p.asBoolean }.getOrNull()
    }

    private fun readFloat(el: com.google.gson.JsonElement?): Float? {
        if (el == null || !el.isJsonPrimitive) return null
        val p = el.asJsonPrimitive
        if (!p.isNumber) return null
        return runCatching { p.asFloat }.getOrNull()
    }
}
