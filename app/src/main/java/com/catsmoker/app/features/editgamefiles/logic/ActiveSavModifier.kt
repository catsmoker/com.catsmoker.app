package com.catsmoker.app.features.editgamefiles.logic

import java.io.File
import java.io.RandomAccessFile

object ActiveSavModifier {
    fun modifyValue(file: File, offset: Long, value: Int) {
        try {
            RandomAccessFile(file, "rw").use { raf ->
                raf.seek(offset)
                raf.writeInt(value)
            }
        } catch (_: Exception) {}
    }
}
