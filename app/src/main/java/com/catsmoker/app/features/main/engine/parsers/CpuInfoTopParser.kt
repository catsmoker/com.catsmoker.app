package com.catsmoker.app.features.main.engine.parsers

data class ProcessCpuInfo(val name: String, val cpuPercent: Float)

object CpuInfoTopParser {
    private val LINE_REGEX = Regex("""\s*([0-9.]+)%\s+\d+/([^:]+):""")
    private val TOTAL_REGEX = Regex("""\s*([0-9.]+)%\s+TOTAL:""")
    private val TOYBOX_TOP_REGEX = Regex("""User\s+([0-9.]+)%,\s+System\s+([0-9.]+)%""")

    fun parseTotalCpu(output: String): Float? {
        val dumpsysMatch = TOTAL_REGEX.find(output)
        if (dumpsysMatch != null) return dumpsysMatch.groupValues[1].toFloatOrNull()

        val topMatch = TOYBOX_TOP_REGEX.find(output)
        if (topMatch != null) {
            val user = topMatch.groupValues[1].toFloatOrNull() ?: 0f
            val sys = topMatch.groupValues[2].toFloatOrNull() ?: 0f
            return user + sys
        }
        
        return null
    }

    fun parseTopProcesses(output: String): List<ProcessCpuInfo> {
        return output.lineSequence()
            .mapNotNull { line ->
                val match = LINE_REGEX.find(line) ?: return@mapNotNull null
                val percent = match.groupValues[1].toFloatOrNull() ?: 0f
                val name = match.groupValues[2].trim()
                if (name == "id") return@mapNotNull null // Filter idle
                ProcessCpuInfo(name, percent)
            }
            .sortedByDescending { it.cpuPercent }
            .take(5)
            .toList()
    }
}
