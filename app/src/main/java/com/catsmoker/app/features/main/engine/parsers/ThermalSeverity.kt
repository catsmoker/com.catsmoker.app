package com.catsmoker.app.features.main.engine.parsers

/**
 * Thermal-severity synthesis: the platform's 0-6 throttle statuses mapped to tiers with
 * human labels, after the reference `ThermalSeverity` + monitor labels
 * (`reference/gamingtools/booster/.../metrics/ThermalSeverity.kt`,
 * `Monitors.kt#statusLabel/pressureLabel`, read before this file was written).
 *
 * Out-of-range statuses map to null — an unknown throttle state is "unknown", never a
 * fabricated severity. Short labels follow the reference overlay's compact set.
 */
object ThermalSeverity {

    enum class Tier {
        NONE,
        LIGHT,
        MODERATE,
        SEVERE,
        CRITICAL,
        EMERGENCY,
        SHUTDOWN
    }

    fun fromStatus(status: Int): Tier? = when (status) {
        0 -> Tier.NONE
        1 -> Tier.LIGHT
        2 -> Tier.MODERATE
        3 -> Tier.SEVERE
        4 -> Tier.CRITICAL
        5 -> Tier.EMERGENCY
        6 -> Tier.SHUTDOWN
        else -> null
    }

    fun label(tier: Tier): String = when (tier) {
        Tier.NONE -> "Normal"
        Tier.LIGHT -> "Light"
        Tier.MODERATE -> "Moderate"
        Tier.SEVERE -> "Severe"
        Tier.CRITICAL -> "Critical"
        Tier.EMERGENCY -> "Emergency"
        Tier.SHUTDOWN -> "Shutdown"
    }

    fun shortLabel(tier: Tier): String = when (tier) {
        Tier.NONE -> "OK"
        Tier.LIGHT -> "WARM"
        Tier.MODERATE -> "HOT"
        Tier.SEVERE, Tier.CRITICAL -> "CRIT"
        Tier.EMERGENCY, Tier.SHUTDOWN -> "!!!"
    }

    /**
     * Display bridge (V8): the compact tier for a raw 0-6 throttle status, or
     * null when there is nothing to show — status 0 (no throttling) and
     * out-of-range (unknown) both yield null, so callers show their primary
     * reading alone instead of fabricating a severity. Throttling statuses
     * yield the overlay-compact label (WARM/HOT/CRIT/!!!).
     */
    fun displayShort(status: Int): String? {
        val tier = fromStatus(status) ?: return null
        if (tier == Tier.NONE) return null
        return shortLabel(tier)
    }
}
