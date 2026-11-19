package com.example.mmdvm

/**
 * 行级 INI 文本编辑：只替换指定键的值，保留其它行、注释与段落顺序。
 * 用于盒子「完整编辑器」（textarea）模式。
 */
internal object IniText {

    fun keyOf(section: String, key: String): String = "$section|$key"

    fun parse(text: String): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        var section = ""
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length - 1).trim()
                return@forEach
            }
            val separator = line.indexOf('=')
            if (section.isEmpty() || separator <= 0) return@forEach
            val key = line.substring(0, separator).trim()
            if (key.isEmpty()) return@forEach
            values[keyOf(section, key)] = stripQuotes(line.substring(separator + 1).trim())
        }
        return values
    }

    fun applyAll(text: String, changes: Map<String, String>): String {
        var result = text
        changes.forEach { (compound, value) ->
            result = set(
                text = result,
                section = compound.substringBefore('|'),
                key = compound.substringAfter('|'),
                value = value
            )
        }
        return result
    }

    fun set(text: String, section: String, key: String, value: String): String {
        val lines = text.split("\n").toMutableList()
        var currentSection = ""
        var sectionFound = false
        var insertAt = -1

        for (index in lines.indices) {
            val raw = lines[index]
            val trimmed = raw.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.substring(1, trimmed.length - 1).trim()
                if (currentSection.equals(section, ignoreCase = true)) {
                    sectionFound = true
                    insertAt = index + 1
                }
                continue
            }
            if (!sectionFound || !currentSection.equals(section, ignoreCase = true)) continue

            val separator = raw.indexOf('=')
            if (separator > 0 && raw.substring(0, separator).trim().equals(key, ignoreCase = true)) {
                val suffix = if (raw.endsWith("\r")) "\r" else ""
                lines[index] = raw.substring(0, separator) + "=" + value + suffix
                return lines.joinToString("\n")
            }
            insertAt = index + 1
        }

        if (sectionFound) {
            lines.add(insertAt.coerceIn(0, lines.size), "$key=$value")
        } else {
            while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
            if (lines.isNotEmpty()) lines.add("")
            lines.add("[$section]")
            lines.add("$key=$value")
            lines.add("")
        }
        return lines.joinToString("\n")
    }

    private fun stripQuotes(value: String): String =
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }
}
