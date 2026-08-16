package com.catsmoker.app.features.gamingtools.engine.parsers

object DexoptStatusParser {
    fun parse(output: String): Map<String, String> {
        val statuses = HashMap<String, String>()
        var currentPkg: String? = null
        for (rawLine in output.lineSequence()) {
            val line = rawLine.trim()
            if (line.startsWith("[") && line.endsWith("]") && !line.contains("=")) {
                currentPkg = line.substring(1, line.length - 1)
                continue
            }
            val pkg = currentPkg ?: continue
            val statusIdx = line.indexOf("status=")
            if (statusIdx >= 0) {
                val status = line.substring(statusIdx + "status=".length)
                    .takeWhile { it.isLetterOrDigit() || it == '-' }
                if (status.isNotEmpty()) {
                    statuses[pkg] = status
                    currentPkg = null
                }
            }
        }
        return statuses
    }
}
