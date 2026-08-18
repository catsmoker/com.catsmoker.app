package com.catsmoker.app.features.gamingtools.engine.parsers

import org.junit.Assert.assertEquals
import org.junit.Test

class DexoptStatusParserTest {

    @Test
    fun parsesStatusPerPackage() {
        val output = """
            [com.example.app]
              path: /data/app/com.example.app/base.apk
                status=speed-profile
            [com.other.pkg]
                status=verify
        """.trimIndent()
        val result = DexoptStatusParser.parse(output)
        assertEquals(2, result.size)
        assertEquals("speed-profile", result["com.example.app"])
    }
}
