package com.catsmoker.app.features.spoofdevice

/**
 * Device-spoofing method exclusivity: the Xposed framework path (LSPosed or
 * Vector, per-app hooks) and the generated Magisk module (device-wide
 * `system.prop` at boot) rewrite the same device identity through different
 * channels, so both being armed at once is a conflict.
 *
 * Pure decisions only — detection feeds them (heartbeat freshness for Xposed,
 * `/data/adb/modules` presence for Magisk), and no verdict here removes,
 * disables, or otherwise touches anything on the device: confirmation only
 * gates the user's tap, and an unconfirmed tap simply does nothing.
 *
 * Status honesty: a Magisk state that cannot be read (no privilege) reports
 * [MagiskModuleStatus.UNKNOWN], never Absent — absence is only reported after
 * a privileged read found nothing. An UNKNOWN module never gates anything and
 * never raises the conflict banner.
 */
object SpoofMethodConflict {

    /** The generated Magisk module's on-device state, as last probed. */
    enum class MagiskModuleStatus {
        /** Module directory present without a `disable` flag — may rewrite identity at boot. */
        INSTALLED,
        /** Directory present but Magisk-disabled — inert until re-enabled. */
        DISABLED,
        /** Privileged read answered and the directory is not there. */
        ABSENT,
        /** No privilege to read — honestly unknown, never treated as absent. */
        UNKNOWN
    }

    /**
     * Whether both methods may rewrite identity right now: a live Xposed
     * heartbeat plus an installed Magisk module. Either side alone, a
     * disabled module, or an unreadable state is not a conflict.
     */
    fun isConflict(xposedActive: Boolean, magisk: MagiskModuleStatus): Boolean =
        xposedActive && magisk == MagiskModuleStatus.INSTALLED

    /**
     * Whether assigning a profile needs explicit confirmation first: only a
     * fresh assignment (never a removal) against an installed Magisk module.
     */
    fun needsAssignConfirm(profileId: String?, magisk: MagiskModuleStatus): Boolean =
        profileId != null && magisk == MagiskModuleStatus.INSTALLED

    /**
     * Whether generating the Magisk ZIP needs explicit confirmation first:
     * whenever the Xposed side is live (heartbeat) or merely claimed
     * (assignments or ladders that a reboot would arm).
     */
    fun needsGenerateConfirm(
        moduleActive: Boolean,
        hasAssignments: Boolean,
        hasLadders: Boolean
    ): Boolean = moduleActive || hasAssignments || hasLadders

    /**
     * Maps a privileged directory probe to the honest module state. Without
     * privilege nothing can be said; a present directory with a `disable`
     * flag is inert rather than active.
     */
    fun parseMagiskModuleStatus(
        hasPrivilege: Boolean,
        dirExists: Boolean,
        disableExists: Boolean
    ): MagiskModuleStatus = when {
        !hasPrivilege -> MagiskModuleStatus.UNKNOWN
        !dirExists -> MagiskModuleStatus.ABSENT
        disableExists -> MagiskModuleStatus.DISABLED
        else -> MagiskModuleStatus.INSTALLED
    }
}
