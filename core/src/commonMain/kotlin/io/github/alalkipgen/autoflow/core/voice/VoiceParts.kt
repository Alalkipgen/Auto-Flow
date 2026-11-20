package io.github.alalkipgen.autoflow.core.voice

/** Text → voice parts (specs/quick-tools.md §6); pure. */
object VoiceParts {
    /**
     * The parts of [text]: it is cut after `။`, after `.` `?` `!` that end a sentence (followed by a space or the end,
     * so "3.5" stays whole) and at line breaks; the pieces are packed in order into parts of at most [maxChars].
     * A piece longer than that is cut at its last space before the limit, else at the limit. Blank pieces are dropped.
     */
    fun split(text: String, maxChars: Int): List<String> {
        val max = maxChars.coerceAtLeast(1)
        val parts = mutableListOf<String>()
        var current = ""
        for (piece in sentences(text).flatMap { cut(it, max) }) {
            current = when {
                current.isEmpty() -> piece
                current.length + 1 + piece.length <= max -> "$current $piece"
                else -> { parts += current; piece }
            }
        }
        if (current.isNotEmpty()) parts += current
        return parts
    }

    private fun sentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val b = StringBuilder()
        fun end() { b.toString().trim().takeIf { it.isNotEmpty() }?.let(out::add); b.clear() }
        val t = text.replace("\r\n", "\n")
        t.forEachIndexed { i, c ->
            if (c == '\n') return@forEachIndexed end()
            b.append(c)
            val next = t.getOrNull(i + 1)
            if (c == '။' || c in ".?!" && (next == null || next.isWhitespace())) end()
        }
        end()
        return out
    }

    private fun cut(piece: String, max: Int): List<String> {
        val out = mutableListOf<String>()
        var rest = piece
        while (rest.length > max) {
            val at = rest.lastIndexOf(' ', max).takeIf { it > 0 } ?: max
            out += rest.substring(0, at).trim()
            rest = rest.substring(at).trim()
        }
        if (rest.isNotEmpty()) out += rest
        return out
    }
}
