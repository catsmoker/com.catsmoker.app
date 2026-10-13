package com.catsmoker.app.features.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Legal-link placement: the Privacy Policy / Terms of Service rows live in
 * About CatSmoker only. Settings used to duplicate them; the duplication was
 * removed so About stays the single canonical location.
 *
 * Like [com.catsmoker.app.shared.LocaleParityTest], this reads sources from
 * disk — the JVM suite has no Compose runtime, and a screenshot-only check
 * would not stop a re-adding. Both screens live in `src/main`, so one file
 * pair covers the full and Play Store variants together.
 */
class LegalLinksPlacementTest {

    private fun screenSource(name: String): String {
        val direct = File("src/main/java/com/catsmoker/app/features/settings/$name")
        if (direct.isFile) return direct.readText()
        val found = File("app/src/main/java/com/catsmoker/app/features")
            .walkTopDown()
            .firstOrNull { it.name == name }
            ?: error("missing source under test: $name")
        return found.readText()
    }

    private fun aboutSource(): String {
        val direct = File("src/main/java/com/catsmoker/app/features/about/AboutScreen.kt")
        if (direct.isFile) return direct.readText()
        val found = File("app/src/main/java/com/catsmoker/app/features")
            .walkTopDown()
            .firstOrNull { it.name == "AboutScreen.kt" }
            ?: error("missing source under test: AboutScreen.kt")
        return found.readText()
    }

    private fun resValue(key: String): String? {
        // url_* keys stay English-only in values/ (never mirrored).
        for (base in listOf("src/main/res/values", "app/src/main/res/values")) {
            val dir = File(base)
            if (!dir.isDirectory) continue
            dir.listFiles { f -> f.extension == "xml" }.orEmpty().forEach { file ->
                Regex("""<string name="$key"[^>]*>([^<]+)</string>""")
                    .find(file.readText())?.let { return it.groupValues[1] }
            }
        }
        return null
    }

    private fun anySource(name: String): String {
        val bases = listOf(
            "src/main/java/com/catsmoker/app/features",
            "app/src/main/java/com/catsmoker/app/features"
        )
        for (base in bases) {
            File(base).walkTopDown().firstOrNull { it.name == name }?.let { return it.readText() }
        }
        error("missing source under test: $name")
    }

    @Test
    fun legalWebsiteUrlIsExact() {
        assertEquals(
            "https://catsmoker.vercel.app/legal",
            resValue("url_legal")
        )
    }

    @Test
    fun legalWebsiteLinkedFromAllThreeSurfaces() {
        assertTrue(
            "Legal screens must link the website legal page",
            anySource("LegalScreens.kt").contains("url_legal")
        )
        assertTrue(
            "About must link the website legal page",
            aboutSource().contains("url_legal")
        )
        assertTrue(
            "First-launch agreement must link the website legal page",
            anySource("PermissionScreen.kt").contains("url_legal")
        )
    }

    @Test
    fun settingsPresentsNoLegalLinks() {
        val settings = screenSource("SettingsScreen.kt")
        assertFalse(
            "Settings must not link the Privacy Policy (About is canonical)",
            settings.contains("legal_privacy_title")
        )
        assertFalse(
            "Settings must not link the Terms of Service (About is canonical)",
            settings.contains("legal_terms_title")
        )
        assertFalse(
            "Settings must not keep the removed navigation handlers",
            settings.contains("onOpenPrivacy") || settings.contains("onOpenTerms")
        )
    }

    @Test
    fun aboutKeepsAllThreeLegalLinks() {
        val about = aboutSource()
        assertTrue(
            "About must keep the Legal section header",
            about.contains("legal_section_title")
        )
        assertTrue(
            "About must keep the Privacy Policy row",
            about.contains("legal_privacy_title")
        )
        assertTrue(
            "About must keep the Terms of Service row",
            about.contains("legal_terms_title")
        )
        assertTrue(
            "About must keep both navigation handlers",
            about.contains("onPrivacy") && about.contains("onTerms")
        )
    }
}
