package com.catsmoker.app.features.editgamefiles.hsr

import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Honkai: Star Rail's graphics block, as the game stores it — in two shapes across
 * versions. The reference-era shape is one URL-encoded JSON blob under the
 * `GraphicsSettings_Model` key inside its Unity `playerprefs.xml`, plus a handful of
 * sibling XML *int* entries (resolution, master quality, shader warmup, user-save
 * marker, settings version) that are deliberately **not** part of the JSON. Live
 * HSR ≥4.x dropped the blob: the file holds only the sibling ints ([fromFlatMap],
 * `flatFormat`). This type covers the JSON half and mirrors the siblings as plain
 * fields;
 * [HsrGameManager] merges the two on read and splits them back out on write — exactly the
 * split the reference makes, because writing a sibling key into the JSON would silently do
 * nothing and writing a JSON key as a sibling would corrupt the blob.
 *
 * Every field the reference parses is parsed here, including the hidden upscaler toggles
 * ([enableMetalFXSU], [dlssQuality], [enableHalfResTransparent], [enableSelfShadow],
 * [particleTrailSmoothness]): they are round-tripped on write so an editor session cannot
 * reset a value the user set in-game. Their editor surfaces are a separate backlog item; the
 * codec must not wait for the UI.
 *
 * Cross-checked against `reference/gamingtools/hsrgraphicdroid-main/data/GraphicsSettings.kt`
 * (`com.ireddragonicy.hsrgraphicdroid.data.GraphicsSettings`): same JSON keys, same
 * URL-encoding wrapper, same defaults, same value ranges — including the quirks that matter,
 * like [sfxQuality] using a shifted scale (0 is invalid; 1–5 are the real steps) and sibling
 * `graphicsQuality` 0 meaning "Custom", which is what lets the per-slider values survive the
 * game's own preset system.
 */
data class HsrGraphicsSettings(
    // JSON blob members.
    var fps: Int = 60,
    var enableVSync: Boolean = true,
    var renderScale: Double = 1.0,
    var resolutionQuality: Int = 3,
    var shadowQuality: Int = 3,
    var lightQuality: Int = 3,
    var characterQuality: Int = 3,
    var envDetailQuality: Int = 3,
    var reflectionQuality: Int = 3,
    var sfxQuality: Int = 3,
    var bloomQuality: Int = 3,
    var aaMode: Int = 1,
    // Hidden upscaler/detail toggles — round-tripped, no editor surface yet.
    var enableMetalFXSU: Boolean = false,
    var enableHalfResTransparent: Boolean = false,
    var enableSelfShadow: Int = 1,
    var dlssQuality: Int = 0,
    var particleTrailSmoothness: Int = 0,
    // Sibling XML int entries, not part of the JSON blob.
    var screenWidth: Int = 1920,
    var screenHeight: Int = 1080,
    var fullscreenMode: Int = -1,
    var graphicsQuality: Int = 3,
    var enablePsoShaderWarmup: Boolean = true,
    var isUserSave: Int = 1,
    var version: Int = 10
) {

    companion object {
        /** The JSON blob's key inside the playerprefs map. */
        const val PREFS_KEY = "GraphicsSettings_Model"

        /**
         * The reference's five one-tap tiers, in its order. Names stay code-English like the
         * `qualityName()` family — the sliders already name every step in English in all
         * locales, so translated preset names would be the inconsistent ones.
         */
        val GRAPHICS_PRESET_NAMES = listOf("Low", "Medium", "High", "Ultra", "Max")

        fun fromEncoded(encoded: String): HsrGraphicsSettings? = try {
            val json = JSONObject(URLDecoder.decode(encoded, "UTF-8"))
            HsrGraphicsSettings(
                fps = json.optInt("FPS", 60),
                enableVSync = json.optBoolean("EnableVSync", true),
                renderScale = json.optDouble("RenderScale", 1.0),
                resolutionQuality = json.optInt("ResolutionQuality", 3),
                shadowQuality = json.optInt("ShadowQuality", 3),
                lightQuality = json.optInt("LightQuality", 3),
                characterQuality = json.optInt("CharacterQuality", 3),
                envDetailQuality = json.optInt("EnvDetailQuality", 3),
                reflectionQuality = json.optInt("ReflectionQuality", 3),
                sfxQuality = json.optInt("SFXQuality", 3),
                bloomQuality = json.optInt("BloomQuality", 3),
                aaMode = json.optInt("AAMode", 1),
                enableMetalFXSU = json.optBoolean("EnableMetalFXSU", false),
                enableHalfResTransparent = json.optBoolean("EnableHalfResTransparent", false),
                enableSelfShadow = json.optInt("EnableSelfShadow", 1),
                dlssQuality = json.optInt("DlssQuality", 0),
                particleTrailSmoothness = json.optInt("ParticleTrailSmoothness", 0)
            )
        } catch (_: Exception) {
            null
        }

        /**
         * The sibling XML int keys the game reads alongside the blob, mapped to this class's
         * field names. Kept here so the read and the write can never disagree about which
         * entries belong to graphics — the same mistake the reference guards against by keeping
         * the list in one place.
         */
        internal val SIBLING_INT_KEYS = listOf(
            "screenWidth" to "Screenmanager%20Resolution%20Width",
            "screenHeight" to "Screenmanager%20Resolution%20Height",
            "fullscreenMode" to "Screenmanager%20Fullscreen%20mode",
            "graphicsQuality" to "GraphicsSettings_GraphicsQuality",
            "enablePsoShaderWarmup" to "GraphicsSettings_EnablePsoShaderWarmup",
            "isUserSave" to "GraphicsSettings_IsUserSave",
            "version" to "GraphicsSettings_Version"
        )

        /** The fixed resolutions behind the preset chips. The reference's own five. */
        fun resolutionFor(preset: String): Pair<Int, Int>? = when (preset) {
            "360p" -> 640 to 360
            "720p" -> 1280 to 720
            "1080p" -> 1920 to 1080
            "1440p" -> 2560 to 1440
            "4K" -> 3840 to 2160
            else -> null
        }

        /**
         * Reads a flat-format file (live HSR ≥4.x: sibling ints, no
         * `GraphicsSettings_Model` blob) into settings. JSON-backed fields keep
         * their defaults — they are unread, not zero — so a flat file never
         * fabricates slider values the game never held.
         */
        fun fromFlatMap(map: Map<String, HsrPlayerPrefsXml.Value>): HsrGraphicsSettings {
            fun int(key: String, default: Int): Int =
                (map[key] as? HsrPlayerPrefsXml.Value.IntValue)?.value ?: default
            val base = HsrGraphicsSettings()
            return base.copy(
                screenWidth = int("Screenmanager%20Resolution%20Width", base.screenWidth),
                screenHeight = int("Screenmanager%20Resolution%20Height", base.screenHeight),
                fullscreenMode = int("Screenmanager%20Fullscreen%20mode", base.fullscreenMode),
                graphicsQuality = int("GraphicsSettings_GraphicsQuality", base.graphicsQuality),
                enablePsoShaderWarmup = int("GraphicsSettings_EnablePsoShaderWarmup", 1) == 1,
                isUserSave = int("GraphicsSettings_IsUserSave", base.isUserSave),
                version = int("GraphicsSettings_Version", base.version)
            )
        }

        /**
         * The sibling-int updates a flat write applies: only keys already present in
         * the live file, so a flat write never invents entries the game never wrote
         * (and never invents the blob either — flat files stay blob-free).
         */
        fun flatUpdates(
            presentKeys: Set<String>,
            settings: HsrGraphicsSettings
        ): Map<String, HsrPlayerPrefsXml.Value.IntValue> {
            val updates = linkedMapOf<String, HsrPlayerPrefsXml.Value.IntValue>()
            for ((field, key) in SIBLING_INT_KEYS) {
                if (key !in presentKeys) continue
                val value = when (field) {
                    "screenWidth" -> settings.screenWidth
                    "screenHeight" -> settings.screenHeight
                    "fullscreenMode" -> settings.fullscreenMode
                    "graphicsQuality" -> settings.graphicsQuality
                    "enablePsoShaderWarmup" -> if (settings.enablePsoShaderWarmup) 1 else 0
                    "isUserSave" -> settings.isUserSave
                    "version" -> settings.version
                    else -> continue
                }
                updates[key] = HsrPlayerPrefsXml.Value.IntValue(value)
            }
            return updates
        }
    }

    fun toEncoded(): String {
        val json = JSONObject()
        json.put("FPS", fps)
        json.put("EnableVSync", enableVSync)
        json.put("RenderScale", renderScale)
        json.put("ResolutionQuality", resolutionQuality)
        json.put("ShadowQuality", shadowQuality)
        json.put("LightQuality", lightQuality)
        json.put("CharacterQuality", characterQuality)
        json.put("EnvDetailQuality", envDetailQuality)
        json.put("ReflectionQuality", reflectionQuality)
        json.put("SFXQuality", sfxQuality)
        json.put("BloomQuality", bloomQuality)
        json.put("AAMode", aaMode)
        json.put("EnableMetalFXSU", enableMetalFXSU)
        json.put("EnableHalfResTransparent", enableHalfResTransparent)
        json.put("EnableSelfShadow", enableSelfShadow)
        json.put("DlssQuality", dlssQuality)
        json.put("ParticleTrailSmoothness", particleTrailSmoothness)
        return URLEncoder.encode(json.toString(), "UTF-8")
    }

    /**
     * The plain-English name of one 0–5 quality step. The reference's own mapping, verbatim:
     * 0 Very Low … 5 Ultra. SFX is the exception — see [sfxQualityName].
     */
    fun qualityName(quality: Int): String = when (quality) {
        0 -> "Very Low"
        1 -> "Low"
        2 -> "Medium"
        3 -> "High"
        4 -> "Very High"
        5 -> "Ultra"
        else -> "Unknown"
    }

    /** SFX's own scale: 0 is "invalid" (the game resets it), 1–5 are the steps. */
    fun sfxQualityName(quality: Int): String = when (quality) {
        0 -> "Invalid"
        1 -> "Very Low"
        2 -> "Low"
        3 -> "Medium"
        4 -> "High"
        5 -> "Very High"
        else -> "Unknown"
    }

    fun aaModeName(): String = when (aaMode) {
        0 -> "Off"
        1 -> "TAA"
        2 -> "FXAA"
        else -> "Off"
    }

    /**
     * Names for the hidden upscaler/detail toggles. Cross-checked against the reference's
     * `getSelfShadowName`/`getDlssName`/`getParticleTrailName` — including that Self Shadow
     * is a 0–2 level (not a bool) and Particle Trail Smoothness a 0–3 level.
     */
    fun selfShadowName(): String = when (enableSelfShadow) {
        0 -> "Off"
        1 -> "Low"
        2 -> "High"
        else -> "Off"
    }

    fun dlssName(): String = when (dlssQuality) {
        0 -> "Off"
        1 -> "Quality"
        2 -> "Balanced"
        3 -> "Performance"
        4 -> "Ultra Performance"
        else -> "Off"
    }

    fun particleTrailName(): String = when (particleTrailSmoothness) {
        0 -> "Off"
        1 -> "Low"
        2 -> "Medium"
        3 -> "High"
        else -> "Off"
    }

    fun resolutionPreset(): String = when {
        screenWidth == 640 && screenHeight == 360 -> "360p"
        screenWidth == 1280 && screenHeight == 720 -> "720p"
        screenWidth == 1920 && screenHeight == 1080 -> "1080p"
        screenWidth == 2560 && screenHeight == 1440 -> "1440p"
        screenWidth == 3840 && screenHeight == 2160 -> "4K"
        else -> "${screenWidth}x${screenHeight}"
    }

    fun applyResolutionPreset(preset: String) {
        resolutionFor(preset)?.let { (width, height) ->
            screenWidth = width
            screenHeight = height
        }
    }

    /**
     * A copy with one of the reference's five one-tap tiers applied (see
     * `GraphicsViewModel.applyPreset`, ported field-for-field): all tiers raise, none caps —
     * Ultra and Max both allow 120 FPS — and `graphicsQuality` drops to 0 (Custom) so the
     * per-slider values survive the game's own preset system. Resolution siblings, the PSO
     * warmup flag and fields the reference leaves alone at a tier ride through untouched;
     * an unknown level returns the copy unchanged rather than inventing a tier.
     */
    fun withGraphicsPreset(level: Int): HsrGraphicsSettings {
        val out = copy(graphicsQuality = 0)
        when (level) {
            0 -> { // Low
                out.fps = 30
                out.enableVSync = true
                out.renderScale = 0.6
                out.resolutionQuality = 0
                out.shadowQuality = 0
                out.lightQuality = 0
                out.characterQuality = 0
                out.envDetailQuality = 0
                out.reflectionQuality = 0
                out.sfxQuality = 1
                out.bloomQuality = 0
                out.aaMode = 0
                out.enableSelfShadow = 0
                out.dlssQuality = 0
                out.particleTrailSmoothness = 0
                out.enableMetalFXSU = false
                out.enableHalfResTransparent = false
            }
            1 -> { // Medium
                out.fps = 60
                out.enableVSync = true
                out.renderScale = 0.8
                out.resolutionQuality = 1
                out.shadowQuality = 1
                out.lightQuality = 1
                out.characterQuality = 1
                out.envDetailQuality = 1
                out.reflectionQuality = 1
                out.sfxQuality = 2
                out.bloomQuality = 1
                out.aaMode = 1
                out.enableSelfShadow = 0
                out.dlssQuality = 0
                out.particleTrailSmoothness = 1
            }
            2 -> { // High
                out.fps = 60
                out.enableVSync = true
                out.renderScale = 1.0
                out.resolutionQuality = 2
                out.shadowQuality = 2
                out.lightQuality = 2
                out.characterQuality = 2
                out.envDetailQuality = 2
                out.reflectionQuality = 2
                out.sfxQuality = 3
                out.bloomQuality = 2
                out.aaMode = 1
                out.enableSelfShadow = 1
                out.dlssQuality = 1
                out.particleTrailSmoothness = 2
            }
            3 -> { // Ultra
                out.fps = 120
                out.enableVSync = false
                out.renderScale = 1.2
                out.resolutionQuality = 3
                out.shadowQuality = 3
                out.lightQuality = 3
                out.characterQuality = 3
                out.envDetailQuality = 3
                out.reflectionQuality = 3
                out.sfxQuality = 4
                out.bloomQuality = 3
                out.aaMode = 1
                out.enableSelfShadow = 2
                out.dlssQuality = 1
                out.particleTrailSmoothness = 3
            }
            4 -> { // Max
                out.fps = 120
                out.enableVSync = false
                out.renderScale = 2.0
                out.resolutionQuality = 5
                out.shadowQuality = 5
                out.lightQuality = 5
                out.characterQuality = 5
                out.envDetailQuality = 5
                out.reflectionQuality = 5
                out.sfxQuality = 5
                out.bloomQuality = 5
                out.aaMode = 1
                out.enableSelfShadow = 2
                out.enableMetalFXSU = true
                out.dlssQuality = 1
                out.particleTrailSmoothness = 3
            }
            else -> return copy()
        }
        return out
    }
}
