package com.catsmoker.app.util

object ActiveSavModifier {
    fun modifyFps(input: ByteArray, targetFps: Int): ByteArray {
        val pattern = byteArrayOf(0x06, 0x00, 0x00, 0x00, 0x46, 0x50, 0x53, 0x4C, 0x65, 0x76, 0x65, 0x6C, 0x00)
        val index = findPattern(input, pattern)
        if (index != -1) {
            val valueIndex = index + pattern.size + 4
            if (valueIndex < input.size) {
                input[valueIndex] = targetFps.toByte()
            }
        }
        return input
    }

    private fun findPattern(data: ByteArray, pattern: ByteArray): Int {
        for (i in 0..data.size - pattern.size) {
            var found = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }
}
