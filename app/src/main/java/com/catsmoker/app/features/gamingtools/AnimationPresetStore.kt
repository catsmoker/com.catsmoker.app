package com.catsmoker.app.features.gamingtools

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

/**
 * Named animation-scale preset library: save the current triple, list it, apply it, delete it.
 *
 * Ported from `reference/gamingtools/Custom-Animations/.../utils/PresetManager.kt` (read in
 * full): UUID-keyed named presets holding window/transition/animator scales, save / list /
 * delete, corrupt storage reading back as empty rather than crashing. Two deliberate
 * divergences: Gson in the store's own file instead of SharedPreferences (JVM-testable,
 * atomically written, and every new feature gets its own file), and save-time validation the reference never had — a blank name or a
 * non-finite / negative / above-10x scale is refused (null) instead of stored, because a
 * preset is a promise the apply path will write those exact values into `Settings.Global`.
 */
class AnimationPresetStore(private val storeFile: File) {

    constructor(context: Context) : this(File(context.filesDir, FILE_NAME))

    data class AnimationPreset(
        val id: String,
        val name: String,
        val window: Float,
        val transition: Float,
        val animator: Float
    )

    /**
     * Stores a preset, or null when [name] is blank or any scale is not a finite 0..10 value.
     * A failed write is also null — history is not worth crashing over, and a preset library
     * doubly so.
     */
    fun savePreset(name: String, window: Float, transition: Float, animator: Float): AnimationPreset? {
        if (name.isBlank()) return null
        for (scale in listOf(window, transition, animator)) {
            if (!scale.isFinite() || scale < 0f || scale > MAX_SCALE) return null
        }
        val presets = loadLocked().toMutableList()
        val preset = AnimationPreset(
            id = UUID.randomUUID().toString(),
            name = name.trim(),
            window = window,
            transition = transition,
            animator = animator
        )
        presets.add(preset)
        return if (saveLocked(presets)) preset else null
    }

    fun getAll(): List<AnimationPreset> = loadLocked()

    /** True when an entry with [id] existed and is now gone. */
    fun delete(id: String): Boolean {
        val presets = loadLocked().toMutableList()
        val removed = presets.removeAll { it.id == id }
        return removed && saveLocked(presets)
    }

    /** A corrupt or absent file is an empty library, not a crash. */
    private fun loadLocked(): List<AnimationPreset> {
        if (!storeFile.exists()) return emptyList()
        return try {
            val type = object : TypeToken<List<AnimationPreset>>() {}.type
            gson.fromJson<List<AnimationPreset>>(storeFile.readText(), type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveLocked(presets: List<AnimationPreset>): Boolean {
        return try {
            val tmp = File(storeFile.parentFile, "${storeFile.name}.tmp-${System.nanoTime()}")
            try {
                tmp.writeText(gson.toJson(presets))
                if (!tmp.renameTo(storeFile)) {
                    if (storeFile.exists() && !storeFile.delete()) return false
                    tmp.copyTo(storeFile, overwrite = true)
                    tmp.delete()
                }
            } finally {
                if (tmp.exists()) tmp.delete()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private val gson = Gson()

    companion object {
        private const val FILE_NAME = "anim_presets.json"

        /** Scales above this are refused at save time (see class KDoc). */
        const val MAX_SCALE = 10f
    }
}
