package com.catsmoker.app.util

object CpuInfoTopParser {
    data class TopProcess(val name: String, val cpuPercent: Float)

    fun parseTopProcesses(output: String): List<TopProcess> {
        val lines = output.lines()
        val processes = mutableListOf<TopProcess>()
        val regex = Regex("""\s*(\d+)%\s+(\d+)/(.*):\s+.*""")
        
        for (line in lines) {
            val match = regex.find(line)
            if (match != null) {
                val cpu = match.groupValues[1].toFloatOrNull() ?: 0f
                val name = match.groupValues[3].trim()
                if (name.isNotEmpty() && name != "id") {
                    processes.add(TopProcess(name, cpu))
                }
            }
        }
        return processes.sortedByDescending { it.cpuPercent }.take(5)
    }
}
