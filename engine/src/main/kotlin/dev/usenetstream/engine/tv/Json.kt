package dev.usenetstream.engine.tv

import java.io.IOException

/** Just enough JSON for TVmaze: objects -> Map, arrays -> List, numbers -> Double, plus String/Boolean/null. */
internal object Json {
    fun parse(text: String): Any? = Parser(text).run { value().also { skipWs(); if (i != s.length) fail("trailing data") } }

    private class Parser(val s: String) {
        var i = 0

        fun value(): Any? {
            skipWs()
            if (i >= s.length) fail("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("unexpected '$c'")
            }
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++
            skipWs()
            if (peek() == '}') { i++; return m }
            while (true) {
                skipWs()
                val k = str()
                skipWs()
                expect(':')
                m[k] = value()
                skipWs()
                when (s.getOrNull(i++)) {
                    ',' -> continue
                    '}' -> return m
                    else -> fail("expected , or }")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++
            skipWs()
            if (peek() == ']') { i++; return l }
            while (true) {
                l += value()
                skipWs()
                when (s.getOrNull(i++)) {
                    ',' -> continue
                    ']' -> return l
                    else -> fail("expected , or ]")
                }
            }
        }

        private fun str(): String {
            expect('"')
            val b = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                when (val c = s[i++]) {
                    '"' -> return b.toString()
                    '\\' -> {
                        when (val e = s[i++]) {
                            'n' -> b.append('\n')
                            't' -> b.append('\t')
                            'r' -> b.append('\r')
                            'b' -> b.append('\b')
                            'f' -> b.append('\u000C')
                            'u' -> { b.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                            else -> b.append(e)
                        }
                    }
                    else -> b.append(c)
                }
            }
        }

        private fun num(): Double {
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(start, i).toDouble()
        }

        private fun literal(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("bad literal")
            i += word.length
            return v
        }

        private fun peek() = s.getOrNull(i)
        private fun expect(c: Char) { if (s.getOrNull(i++) != c) fail("expected '$c'") }
        fun skipWs() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun fail(msg: String): Nothing = throw IOException("bad JSON at $i: $msg")
    }
}

@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(): Map<String, Any?>? = this as? Map<String, Any?>
internal fun Any?.list(): List<Any?> = this as? List<Any?> ?: emptyList()
internal fun Map<String, Any?>.str(key: String): String? = this[key] as? String
internal fun Map<String, Any?>.int(key: String): Int? = (this[key] as? Double)?.toInt()
