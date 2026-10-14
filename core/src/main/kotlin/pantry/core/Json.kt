package pantry.core

class CodecException(message: String) : Exception(message)

/**
 * Minimal JSON. Parsed values are Map<String, Any?>, List<Any?>, String, Double, Boolean or null.
 * The writer emits one compact line, which is what the event log needs: one record per line.
 */
object Json {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (!p.atEnd()) throw CodecException("trailing characters at ${p.pos}")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> =
        parse(text) as? Map<String, Any?> ?: throw CodecException("not a JSON object")

    /** Writes a flat or nested structure on one line. Map keys are written in iteration order. */
    fun write(value: Any?): String = StringBuilder().also { writeTo(it, value) }.toString()

    private fun writeTo(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> writeString(sb, v)
            is Boolean -> sb.append(v.toString())
            is Int, is Long -> sb.append(v.toString())
            is Double -> sb.append(if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(sb, k.toString())
                    sb.append(':')
                    writeTo(sb, x)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, x ->
                    if (i > 0) sb.append(',')
                    writeTo(sb, x)
                }
                sb.append(']')
            }
            else -> throw CodecException("cannot write ${v::class.simpleName}")
        }
    }

    private fun writeString(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun value(): Any? {
            if (atEnd()) throw CodecException("unexpected end")
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw CodecException("unexpected '$c' at $pos")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, pos)) throw CodecException("bad literal at $pos")
            pos += word.length
            return v
        }

        private fun num(): Double {
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] in "+-.eE")) pos++
            return s.substring(start, pos).toDoubleOrNull() ?: throw CodecException("bad number at $start")
        }

        private fun str(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw CodecException("unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw CodecException("bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (pos + 4 > s.length) throw CodecException("bad \\u escape")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw CodecException("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun arr(): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            skipWs()
            if (s.getOrNull(pos) == ']') { pos++; return out }
            while (true) {
                skipWs()
                out.add(value())
                skipWs()
                when (s.getOrNull(pos++)) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw CodecException("expected , or ] at ${pos - 1}")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (s.getOrNull(pos) == '}') { pos++; return out }
            while (true) {
                skipWs()
                if (s.getOrNull(pos) != '"') throw CodecException("expected key at $pos")
                val k = str()
                skipWs()
                if (s.getOrNull(pos++) != ':') throw CodecException("expected : at ${pos - 1}")
                skipWs()
                out[k] = value()
                skipWs()
                when (s.getOrNull(pos++)) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw CodecException("expected , or } at ${pos - 1}")
                }
            }
        }
    }
}

/**
 * Files of flat records, one JSON object per line, every value a string.
 * Numbers and booleans are rejected on read, so a hand-edited barcode like 012345 cannot
 * silently lose its leading zero.
 */
object Jsonl {

    fun readRecords(text: String): List<Map<String, String>> {
        val out = ArrayList<Map<String, String>>()
        text.lines().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachIndexed
            val ln = i + 1
            val obj = try { Json.parseObject(line) } catch (e: CodecException) { throw CodecException("line $ln: ${e.message}") }
            val rec = LinkedHashMap<String, String>()
            for ((k, v) in obj) {
                when (v) {
                    null -> {}
                    is String -> rec[k] = v
                    else -> throw CodecException("line $ln: \"$k\" must be a string, put quotes around it")
                }
            }
            out += rec
        }
        return out
    }

    fun writeRecords(records: List<Map<String, String>>): String =
        records.joinToString("") { Json.write(it) + "\n" }
}
