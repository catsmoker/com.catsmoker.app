package com.catsmoker.app.features.settings

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GitHub-release selection for the full-variant self-updater, driven by the
 * real API shape (fetched 2026-10-09): tags are `{versionCode}-{versionName}`
 * (`9-2.0.3`), draft/prerelease flags are present, and APK assets carry a
 * `digest: "sha256:..."` plus size. Fixtures below are trimmed to those
 * fields so the tests pin the contract without snapshotting 100 KB of JSON.
 */
class SelfUpdaterTest {

    private fun release(
        tag: String,
        prerelease: Boolean = false,
        draft: Boolean = false,
        assets: String = ""
    ): String =
        """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease,"body":"notes","assets":[$assets]}"""

    private fun apkAsset(
        name: String = "app-full-release.apk",
        url: String = "https://github.com/catsmoker/catsmoker-app/releases/download/9-2.0.3/app-full-release.apk",
        digest: String = "sha256:6dacd0be509334a0ffa7457f6b08a40e30c9f1df7b1dee78b1424eaf4f70ace1",
        size: Long = 5469625L
    ): String =
        """{"name":"$name","browser_download_url":"$url","digest":"$digest","size":$size}"""

    private fun arrayOf(vararg releases: String): JSONArray =
        JSONArray("[${releases.joinToString(",")}]")

    @Test
    fun tagWithCodePrefixParses() {
        assertEquals(
            SelfUpdater.TagVersion(code = 9, name = "2.0.3"),
            SelfUpdater.parseTagVersion("9-2.0.3")
        )
    }

    @Test
    fun legacyTagWithoutCodeFallsBackToNameOnly() {
        assertEquals(
            SelfUpdater.TagVersion(code = null, name = "v2.0.3"),
            SelfUpdater.parseTagVersion("v2.0.3")
        )
    }

    @Test
    fun selectsNewerStableWithDigest() {
        val info = SelfUpdater.selectUpdate(
            arrayOf(release("9-2.0.3", assets = apkAsset())),
            currentCode = 8, currentName = "2.0.2", wantPreRelease = false
        )
        assertTrue(info != null)
        assertEquals("9-2.0.3", info!!.tagName)
        assertTrue(info.downloadUrl!!.endsWith("app-full-release.apk"))
        assertEquals(
            "6dacd0be509334a0ffa7457f6b08a40e30c9f1df7b1dee78b1424eaf4f70ace1",
            info.sha256Hex
        )
        assertEquals(5469625L, info.sizeBytes)
        assertEquals("notes", info.notes)
    }

    @Test
    fun installedLatestIsUpToDate() {
        assertNull(
            SelfUpdater.selectUpdate(
                arrayOf(release("9-2.0.3", assets = apkAsset())),
                currentCode = 9, currentName = "2.0.3", wantPreRelease = false
            )
        )
    }

    @Test
    fun neverDowngrades() {
        assertNull(
            SelfUpdater.selectUpdate(
                arrayOf(release("7-2.0.1", assets = apkAsset())),
                currentCode = 9, currentName = "2.0.3", wantPreRelease = false
            )
        )
    }

    @Test
    fun skipsPrereleasesUnlessWanted() {
        val releases = arrayOf(
            release("10-2.1.0-beta", prerelease = true, assets = apkAsset()),
            release("9-2.0.3", assets = apkAsset())
        )
        assertEquals(
            "9-2.0.3",
            SelfUpdater.selectUpdate(releases, 8, "2.0.2", wantPreRelease = false)!!.tagName
        )
        assertEquals(
            "10-2.1.0-beta",
            SelfUpdater.selectUpdate(releases, 8, "2.0.2", wantPreRelease = true)!!.tagName
        )
    }

    @Test
    fun allPrereleasesMeansNoStable() {
        // Regression: the old code fell through to releases[0], offering a
        // prerelease as "stable". No stable candidate means no update.
        assertNull(
            SelfUpdater.selectUpdate(
                arrayOf(release("10-2.1.0-beta", prerelease = true, assets = apkAsset())),
                currentCode = 8, currentName = "2.0.2", wantPreRelease = false
            )
        )
    }

    @Test
    fun skipsDrafts() {
        assertNull(
            SelfUpdater.selectUpdate(
                arrayOf(release("10-2.1.0", draft = true, assets = apkAsset())),
                currentCode = 8, currentName = "2.0.2", wantPreRelease = false
            )
        )
    }

    @Test
    fun releaseWithoutApkHasNullUrl() {
        val info = SelfUpdater.selectUpdate(
            arrayOf(release("9-2.0.3")),
            currentCode = 8, currentName = "2.0.2", wantPreRelease = false
        )
        assertTrue(info != null)
        assertNull(info!!.downloadUrl)
    }

    @Test
    fun installedPendingTagCountsAsUpdated() {
        assertTrue(SelfUpdater.isTagInstalled("9-2.0.3", currentCode = 9, currentName = "2.0.3"))
        assertTrue(SelfUpdater.isTagInstalled("9-2.0.3", currentCode = 10, currentName = "2.0.4"))
        assertFalse(SelfUpdater.isTagInstalled("10-2.1.0", currentCode = 9, currentName = "2.0.3"))
    }

    @Test
    fun legacyNameCompareWithoutCodePrefix() {
        // Tags without a numeric prefix compare by semver name only.
        assertTrue(
            SelfUpdater.selectUpdate(
                arrayOf(release("v2.0.4", assets = apkAsset())),
                currentCode = null, currentName = "v2.0.3", wantPreRelease = false
            ) != null
        )
        assertNull(
            SelfUpdater.selectUpdate(
                arrayOf(release("v2.0.3", assets = apkAsset())),
                currentCode = null, currentName = "v2.0.3", wantPreRelease = false
            )
        )
    }
}
