package com.catsmoker.app.shared.util

import android.os.Build

/**
 * True on vivo and iQOO builds, which several gaming tweaks special-case.
 *
 * A top-level function rather than a method on an injected class: it reads nothing but [Build], so
 * requiring a `Context` to answer it would be a dependency that buys nothing. It used to sit on the
 * class that is now `DisplayRefreshRateProvider`, beside the refresh-rate reads — one vendor check
 * next to two display reads is what forced that class to carry a name as vague as "diagnostic
 * manager".
 *
 * Both `MANUFACTURER` and `BRAND` are checked, because either field can carry the vendor name while
 * the other carries something else.
 */
fun isVivoOrIqoo(): Boolean {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val brand = Build.BRAND.lowercase()
    return manufacturer.contains("vivo") || manufacturer.contains("iqoo") ||
        brand.contains("vivo") || brand.contains("iqoo")
}
