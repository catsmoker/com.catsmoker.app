package com.catsmoker.app.spoofing.nonroot

/**
 * Utility for performing in-place dynamic binary modifications on Unreal Engine 4 Active.sav save files.
 */
object ActiveSavModifier {

    private val INT_PROP_HEADER = byteArrayOf(
        0x0c, 0x00, 0x00, 0x00,
        'I'.code.toByte(), 'n'.code.toByte(), 't'.code.toByte(), 'P'.code.toByte(),
        'r'.code.toByte(), 'o'.code.toByte(), 't'.code.toByte(), 'e'.code.toByte(),
        'r'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 0x00,
        0x04, 0x00, 0x00, 0x00, // Property Data Size: 4 bytes
        0x00, 0x00, 0x00, 0x00, // Array Index: 0
        0x00                    // Struct/Property Null Terminator
    )

    /**
     * Modifies FPS properties (BattleFPS, LobbyFPS, FPSLevel) in the binary Active.sav data.
     * @param savData Original byte array of Active.sav
     * @param targetFps Target FPS level (e.g. 8 for 120 FPS, 6 for 90 FPS)
     * @return Modified byte array with updated FPS settings
     */
    fun modifyFps(savData: ByteArray, targetFps: Int = 8): ByteArray {
        val data = savData.clone()
        val props = listOf("BattleFPS", "LobbyFPS", "FPSLevel")

        for (prop in props) {
            val propBytes = (prop + "\u0000").toByteArray(Charsets.US_ASCII)
            val pattern = propBytes + INT_PROP_HEADER

            var index = 0
            while (true) {
                index = indexOf(data, pattern, index)
                if (index == -1) break

                val valOffset = index + pattern.size
                if (valOffset + 4 <= data.size) {
                    data[valOffset] = (targetFps and 0xFF).toByte()
                    data[valOffset + 1] = ((targetFps shr 8) and 0xFF).toByte()
                    data[valOffset + 2] = ((targetFps shr 16) and 0xFF).toByte()
                    data[valOffset + 3] = ((targetFps shr 24) and 0xFF).toByte()
                }
                index += pattern.size
            }
        }
        return data
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, startFrom: Int): Int {
        if (pattern.isEmpty() || startFrom < 0) return -1
        val maxStart = data.size - pattern.size
        if (maxStart < startFrom) return -1

        outer@ for (i in startFrom..maxStart) {
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
