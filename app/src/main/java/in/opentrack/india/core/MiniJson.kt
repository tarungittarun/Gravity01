package `in`.opentrack.india.core

/**
 * A ~110-line JSON reader, instead of Gson/Moshi/`org.json`.
 *
 * The app parses two payload shapes (a user-configured live endpoint and our own snapshot format),
 * and both must be unit-testable on a plain JVM. `org.json` ships on the Android framework but is a
 * stub in JVM unit tests; Gson/Moshi add ~300 kB plus a dependency tree for one recursive descent.
 * "AndroidX + coroutines only" is a stated requirement, and a parse rule readable in one screen is
 * the property this app actually needs.
 *
 * Strict syntax, tolerant consumers ([obj]/[arr] yield null on error), size- and depth-capped: a
 * half-parsed live status must never reach a traveller as fact.
 */
object MiniJson {

    private const val MAX_DEPTH = 24
    private const val MAX_INPUT = 4 * 1024 * 1024

    sealed class Result {
        data class Value(val any: Any?) : Result()
        data class Error(val message: String, val offset: Int) : Result()
    }

    fun parse(text: String): Result {
        if (text.length > MAX_INPUT) return Result.Error("input too large", 0)
        val p = Parser(text)
        return try {
            p.skipWs()
            val v = p.value(0)
            p.skipWs()
            if (!p.atEnd()) return Result.Error("trailing content", p.i)
            Result.Value(v)
        } catch (e: Exception) {
            Result.Error(e.message ?: "parse error", p.i)
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(text: String): Map<String, Any?>? = (parse(text) as? Result.Value)?.any as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun arr(text: String): List<Any?>? = (parse(text) as? Result.Value)?.any as? List<Any?>

    fun Map<String, Any?>.str(key: String): String? = (this[key] as? String)?.takeIf { it.isNotEmpty() }

    fun Map<String, Any?>.int(key: String): Int? = when (val v = this[key]) {
        is Double -> v.toInt(); is Long -> v.toInt(); is Int -> v
        is String -> v.trim().toIntOrNull(); else -> null
    }

    fun Map<String, Any?>.bool(key: String): Boolean? = when (val v = this[key]) {
        is Boolean -> v; is Double -> v != 0.0
        is String -> v.equals("true", true) || v == "1"; else -> null
    }

    @Suppress("UNCHECKED_CAST")
    fun Map<String, Any?>.map(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    fun Map<String, Any?>.list(key: String): List<Any?>? = this[key] as? List<Any?>

    private class Parser(private val s: String) {
        var i = 0
        fun atEnd(): Boolean = i >= s.length
        private fun peek(): Char = s[i]
        private fun fail(msg: String): Nothing = throw IllegalArgumentException("$msg at $i")

        fun skipWs() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++ else break
            }
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("too deep")
            skipWs()
            if (atEnd()) fail("unexpected end")
            return when (peek()) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            skipWs()
            if (!atEnd() && peek() == '}') { i++; return out }
            while (true) {
                skipWs()
                if (atEnd() || peek() != '"') fail("expected key")
                val key = string()
                skipWs()
                if (atEnd() || peek() != ':') fail("expected ':'")
                i++
                out[key] = value(depth + 1)
                skipWs()
                if (atEnd()) fail("unterminated object")
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun array(depth: Int): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            skipWs()
            if (!atEnd() && peek() == ']') { i++; return out }
            while (true) {
                out.add(value(depth + 1))
                skipWs()
                if (atEnd()) fail("unterminated array")
                when (peek()) {
                    ',' -> {
                        i++
                        skipWs()
                        if (!atEnd() && peek() == ']') { i++; return out }
                    }
                    ']' -> { i++; return out }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun string(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c != '\\') { sb.append(c); continue }
                if (atEnd()) fail("bad escape")
                when (val e = s[i++]) {
                    '"', '\\', '/', 'u' -> if (e == 'u') sb.append(unicode()) else sb.append(e)
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    else -> sb.append(e)
                }
            }
        }

        private fun unicode(): Char {
            if (i + 4 > s.length) fail("bad \\u escape")
            val hex = s.substring(i, i + 4)
            i += 4
            return hex.toIntOrNull(16)?.toChar() ?: '?'
        }

        private fun number(): Double {
            val start = i
            if (!atEnd() && (peek() == '-' || peek() == '+')) i++
            while (!atEnd() && (peek().isDigit() || peek() == '.' || peek() == 'e' ||
                    peek() == 'E' || peek() == '-' || peek() == '+')) i++
            val text = s.substring(start, i)
            return text.toDoubleOrNull() ?: fail("bad number '$text'")
        }

        private fun literal(word: String, value: Any?): Any? {
            if (!s.startsWith(word, i)) fail("expected '$word'")
            i += word.length
            return value
        }
    }
}
