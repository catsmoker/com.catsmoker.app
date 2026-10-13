// No-op Play shim: parameters mirror the full variant's signatures so shared
// callers compile against both; RedundantSuspendModifier/EmptyMethod/SameReturnValue
// findings here are the seam contract, not dead code — do not "fix" by deletion.
package com.catsmoker.app.features.settings

import android.content.Context
import java.io.File

/**
 * Play Store variant: no self-updater (Play policy forbids downloading APKs
 * outside Play; updates come from the Play Store itself).
 *
 * Same FQN as the full variant's [SelfUpdater] so [SettingsViewModel] links
 * without compiling the GitHub-release implementation into this variant.
 */
object SelfUpdater {
    const val SUPPORTED = false

    data class UpdateInfo(
        val tagName: String,
        val downloadUrl: String?,
        val sha256Hex: String? = null,
        val sizeBytes: Long? = null,
        val notes: String? = null
    )

    sealed interface CheckResult {
        data object NoReleases : CheckResult
        data object UpToDate : CheckResult
        data class Available(val info: UpdateInfo) : CheckResult
        data class Failed(val message: String) : CheckResult
    }

    suspend fun checkForUpdate(isPreRelease: Boolean, versionCode: Int, versionName: String): CheckResult =
        CheckResult.UpToDate

    fun isTagInstalled(tagName: String, currentCode: Int, currentName: String): Boolean = false

    /**
     * Sham mirrors so shared callers compile: the Play variant never reaches
     * verification (every entry point returns before it on SUPPORTED=false),
     * so null/false are the only honest answers here.
     */
    fun sha256HexOf(file: File): String? = null

    fun isCompatibleApk(context: Context, file: File): Boolean = false

    suspend fun downloadUpdate(
        context: Context,
        url: String?,
        onProgress: (Float) -> Unit
    ): File? = null

    fun installUpdate(context: Context, file: File) {
        // No-op: the Play Store owns updates on this variant.
    }
}
