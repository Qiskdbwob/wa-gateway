package com.example.agent.tool

/**
 * Minimal reader for the flat JSON object that OpenAI-compatible models send as function
 * arguments.
 *
 * It exists because `org.json` is only a stub in plain JVM unit tests, and a tool must be
 * testable without Robolectric. Unlike a naive regex search it walks the object once and is
 * string-aware, so a `file content` value that itself contains `"path": "..."` can no longer be
 * mistaken for a real key. Escapes (`\"`, `\n`, `\u00e9`) are decoded, so file contents survive
 * the round trip, and every lookup simply returns null when an argument is absent or malformed —
 * the tool then reports a readable input error instead of throwing.
 */
internal object JsonArgs {

    private class Value(val decoded: String, val raw: String, val isString: Boolean)

    /** Decoded value of [key], or null when the key is absent. */
    fun string(json: String, key: String): String? {
        val value = parse(json)[key] ?: return null
        return if (value.isString) value.decoded else value.raw
    }

    /** Boolean value of [key], accepting `true`/`false` as a bare token or a quoted string. */
    fun boolean(json: String, key: String): Boolean? {
        val value = parse(json)[key] ?: return null
        return when ((if (value.isString) value.decoded else value.raw).trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> null
        }
    }

    /** Integer value of [key], accepting a bare number or a quoted one. */
    fun int(json: String, key: String): Int? {
        val value = parse(json)[key] ?: return null
        return (if (value.isString) value.decoded else value.raw).trim().toIntOrNull()
    }

    /** Top level keys of a JSON object; unknown/malformed input yields an empty map. */
    private fun parse(json: String): Map<String, Value> {
        val values = mutableMapOf<String, Value>()
        val text = json.trim()
        val open = text.indexOf('{')
        if (open < 0) return values

        var i = open + 1
        while (i < text.length) {
            i = skipWhitespace(text, i)
            if (i >= text.length) break

            when (text[i]) {
                '}' -> return values
                ',' -> i++ // stray separator stays harmless
                '"' -> {
                    val key = readString(text, i) ?: return values
                    var cursor = skipWhitespace(text, key.next)
                    if (cursor >= text.length || text[cursor] != ':') return values
                    cursor = skipWhitespace(text, cursor + 1)
                    if (cursor >= text.length) return values

                    values[key.decoded] = if (text[cursor] == '"') {
                        val value = readString(text, cursor) ?: return values
                        i = value.next
                        Value(decoded = value.decoded, raw = text.substring(cursor, value.next), isString = true)
                    } else {
                        val end = readRawEnd(text, cursor)
                        val raw = text.substring(cursor, end).trim()
                        i = end
                        Value(decoded = raw, raw = raw, isString = false)
                    }
                }

                else -> return values // not an object body we understand
            }
        }
        return values
    }

    private class StringCursor(val decoded: String, val next: Int)

    /** Reads a JSON string starting at [start] (which must be `"`); null when unterminated. */
    private fun readString(text: String, start: Int): StringCursor? {
        if (start >= text.length || text[start] != '"') return null
        val out = StringBuilder()
        var i = start + 1
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' -> {
                    i++
                    if (i >= text.length) return null
                    when (val escaped = text[i]) {
                        '"', '\\', '/' -> out.append(escaped)
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> {
                            if (i + 4 >= text.length) return null
                            val code = text.substring(i + 1, i + 5).toIntOrNull(16) ?: return null
                            out.append(code.toChar())
                            i += 4
                        }

                        else -> out.append(escaped)
                    }
                }

                c == '"' -> return StringCursor(out.toString(), i + 1)
                else -> out.append(c)
            }
            i++
        }
        return null
    }

    /** End index (exclusive) of a nested value that is not a string. */
    private fun readRawEnd(text: String, start: Int): Int {
        var i = start
        var depth = 0
        while (i < text.length) {
            when (text[i]) {
                '"' -> {
                    val inner = readString(text, i) ?: return text.length
                    i = inner.next
                    continue
                }

                '{', '[' -> depth++
                '}', ']' -> {
                    if (depth == 0) return i
                    depth--
                }

                ',' -> if (depth == 0) return i
            }
            i++
        }
        return text.length
    }

    private fun skipWhitespace(text: String, start: Int): Int {
        var i = start
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }
}
