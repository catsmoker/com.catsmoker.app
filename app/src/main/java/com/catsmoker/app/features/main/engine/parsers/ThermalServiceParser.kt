package com.catsmoker.app.features.main.engine.parsers

object ThermalServiceParser {
    data class ThermalResult(
        val cpuC: Float = 0f,
        val gpuC: Float = 0f,
        val skinC: Float = 0f,
        val thermalStatus: Int = 0,
        val entryCount: Int = 0
    )

    fun parse(output: String): ThermalResult? {
        if (output.isBlank() || output.contains("not found")) return null
        
        var cpu = 0f
        var gpu = 0f
        var skin = 0f
        var status = 0
        var count = 0
        
        output.lines().forEach { line ->
            val l = line.lowercase()
            when {
                l.contains("cpu") && l.contains("temp") -> {
                    cpu = extractTemp(line); count++
                }
                l.contains("gpu") && l.contains("temp") -> {
                    gpu = extractTemp(line); count++
                }
                l.contains("skin") && l.contains("temp") -> {
                    skin = extractTemp(line); count++
                }
                l.contains("status:") -> {
                    status = line.split(":").last().trim().toIntOrNull() ?: 0
                }
            }
        }
        
        return ThermalResult(cpu, gpu, skin, status, count)
    }

    private fun extractTemp(line: String): Float {
        return Regex("([0-9.]+)").find(line)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
    }
}
