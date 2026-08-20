package dev.ggoggam.vitre.core.bridge

/** Wraps [s] as a safe double-quoted JavaScript string literal. */
internal fun jsString(s: String): String =
    buildString {
        append('"')
        for (c in s) {
            when (val code = c.code) {
                0x08 -> append("\\b")

                0x09 -> append("\\t")

                0x0A -> append("\\n")

                0x0C -> append("\\f")

                0x0D -> append("\\r")

                0x22 -> append("\\\"")

                0x5C -> append("\\\\")

                0x2028 -> append("\\u2028")

                // line separator — breaks raw JS string literals
                0x2029 -> append("\\u2029")

                // paragraph separator — same
                else -> if (code < 0x20) appendUnicode(code) else append(c)
            }
        }
        append('"')
    }

private fun StringBuilder.appendUnicode(code: Int) {
    append("\\u")
    val hex = code.toString(16)
    repeat(4 - hex.length) { append('0') }
    append(hex)
}
