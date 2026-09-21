package rci.json

/** 极简 JSON 解析器：值为 Map<String,Any?> / List<Any?> / String / Double / Boolean / null。 */
object Json {
    fun parse(input: String): Any? {
        val p = Parser(input)
        p.ws()
        val v = p.value()
        p.ws()
        require(p.atEnd()) { "JSON 解析失败：${p.pos} 处仍有内容" }
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(input: String): Map<String, Any?> = parse(input) as Map<String, Any?>

    fun stringify(v: Any?, indent: Boolean = false): String {
        val sb = StringBuilder()
        write(sb, v, 0, indent)
        return sb.toString()
    }

    private fun write(sb: StringBuilder, v: Any?, depth: Int, indent: Boolean) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(v.toString())
            is Number -> {
                val d = v.toDouble()
                if (d.isFinite() && d % 1.0 == 0.0) sb.append(d.toLong().toString())
                else sb.append(d.toString())
            }
            is String -> writeString(sb, v)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append("{")
                v.entries.forEachIndexed { i, e ->
                    if (i > 0) sb.append(",")
                    if (indent) sb.append("\n").append("  ".repeat(depth + 1))
                    writeString(sb, e.key.toString()); sb.append(if (indent) ": " else ":")
                    write(sb, e.value, depth + 1, indent)
                }
                if (indent) sb.append("\n").append("  ".repeat(depth))
                sb.append("}")
            }
            is Iterable<*> -> {
                val list = v.toList()
                if (list.isEmpty()) { sb.append("[]"); return }
                sb.append("[")
                list.forEachIndexed { i, e ->
                    if (i > 0) sb.append(",")
                    if (indent) sb.append("\n").append("  ".repeat(depth + 1))
                    write(sb, e, depth + 1, indent)
                }
                if (indent) sb.append("\n").append("  ".repeat(depth))
                sb.append("]")
            }
            else -> writeString(sb, v.toString())
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (ch in s) when (ch) {
            '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r"); '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b"); '\u000C' -> sb.append("\\f")
            else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0
        fun ws() { while (pos < s.length && s[pos] in " \t\r\n") pos++ }
        fun atEnd() = pos >= s.length
        fun value(): Any? {
            ws()
            if (atEnd()) error("意外结束")
            return when (s[pos]) {
                '{' -> obj(); '[' -> arr(); '"' -> str()
                't' -> lit("true", true); 'f' -> lit("false", false); 'n' -> lit("null", null)
                else -> num()
            }
        }
        private fun lit(k: String, v: Any?): Any? {
            require(s.startsWith(k, pos)) { "JSON 解析失败 @$pos" }; pos += k.length; return v
        }
        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            pos++ // {
            ws(); if (s[pos] == '}') { pos++; return m }
            while (true) {
                ws(); val key = str(); ws(); require(s[pos] == ':') { "需要 : @$pos" }; pos++
                m[key] = value(); ws()
                when (s[pos]) { ',' -> { pos++; continue } '}' -> { pos++; break } else -> error("需要 , 或 } @$pos")
                }
            }
            return m
        }
        private fun arr(): List<Any?> {
            val l = mutableListOf<Any?>(); pos++
            ws(); if (s[pos] == ']') { pos++; return l }
            while (true) {
                l.add(value()); ws()
                when (s[pos]) { ',' -> { pos++; continue } ']' -> { pos++; break } else -> error("需要 , 或 ] @$pos")
                }
            }
            return l
        }
        private fun str(): String {
            require(s[pos] == '"'); pos++
            val sb = StringBuilder()
            while (true) {
                val ch = s[pos++]
                if (ch == '"') break
                if (ch != '\\') { sb.append(ch); continue }
                when (val e = s[pos++]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append(12.toChar())
                    'u' -> {
                        sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4
                    }
                    else -> error("非法转义 \\$e")
                }
            }
            return sb.toString()
        }
        private fun num(): Any {
            val start = pos
            if (s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val t = s.substring(start, pos)
            return t.toDoubleOrNull() ?: error("非法数字 $t")
        }
    }
}
