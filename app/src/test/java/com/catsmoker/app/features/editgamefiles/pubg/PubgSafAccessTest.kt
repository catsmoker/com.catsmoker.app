package com.catsmoker.app.features.editgamefiles.pubg

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The version-branched picker hint's shapes (TODO M200), pinned as strings. */
class PubgSafAccessTest {

    private val pkg = "com.tencent.ig"

    @Test
    fun android14AndUpUseTheZeroWidthSpaceTree() {
        val uri = PubgSafAccess.initialFolderUriString(pkg, 34)!!
        assertTrue(uri.startsWith("content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdat%E2%80%8Ba/document/"))
        assertTrue(uri.endsWith("primary%3AAndroid%2Fdat%E2%80%8Ba%2F$pkg%2F"))
    }

    @Test
    fun android11To13UseThePlainDataTree() {
        for (sdk in listOf(30, 31, 32, 33)) {
            val uri = PubgSafAccess.initialFolderUriString(pkg, sdk)!!
            assertTrue("sdk $sdk", uri.startsWith("content://com.android.externalstorage.documents/tree/primary%3AAndroid%2Fdata/document/"))
            assertTrue("sdk $sdk", uri.endsWith("primary%3AAndroid%2Fdata%2F$pkg%2F"))
            assertFalse("sdk $sdk must not carry the A14 trick", uri.contains("%E2%80%8B"))
        }
    }

    @Test
    fun android10AndBelowGetNoHint() {
        assertNull(PubgSafAccess.initialFolderUriString(pkg, 29))
    }
}
