package com.catsmoker.app.features.editgamefiles.pubg

import android.net.Uri

/**
 * Version-branched starting point for the `ACTION_OPEN_DOCUMENT_TREE` picker that lands on a
 * game's `Android/data/<package>` folder (TODO M200), copied from the eu tool's picker flow.
 *
 * The picker refuses to *navigate* into `Android/data` on Android 11+, so both tools hand
 * `DocumentsContract.EXTRA_INITIAL_URI` a document URI inside the target tree: the plain form on
 * Android 11–13, and — on Android 14+, where the plain form is refused — the same URI with a
 * zero-width space in the `data` path segment (`dat%E2%80%8Ba`), which the reference found the
 * platform resolves back to `data`. That mechanism is the reference's and is unverified on this
 * device (TODO M200); if a build refuses it, the picker simply opens at its default location and
 * the existing manual flow is unchanged. Android 10 and below get no hint — the folder was
 * reachable anyway.
 *
 * The string builder and the `Uri` wrap are split so JVM unit tests can pin the URI shapes:
 * Android's `Uri` is a throwing stub on the local JVM.
 */
object PubgSafAccess {

    private const val AUTHORITY = "com.android.externalstorage.documents"

    /** The `EXTRA_INITIAL_URI` value for [packageName] on [sdk], or null when no hint exists. */
    fun initialFolderUriString(packageName: String, sdk: Int): String? = when {
        sdk > 33 ->
            "content://$AUTHORITY/tree/primary%3AAndroid%2Fdat%E2%80%8Ba/document/" +
                "primary%3AAndroid%2Fdat%E2%80%8Ba%2F$packageName%2F"
        sdk >= 30 ->
            "content://$AUTHORITY/tree/primary%3AAndroid%2Fdata/document/" +
                "primary%3AAndroid%2Fdata%2F$packageName%2F"
        else -> null
    }

    fun initialFolderUri(packageName: String, sdk: Int): Uri? =
        initialFolderUriString(packageName, sdk)?.let(Uri::parse)
}
