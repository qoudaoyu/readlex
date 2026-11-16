package com.readvocab.core.text

/** A word-like token in a piece of text. [start] inclusive, [end] exclusive. */
data class Token(val text: String, val start: Int, val end: Int)

object Tokenizer {
    // Letters, optionally joined by apostrophes (don't, o'clock). Hyphenated words are split into parts.
    private val WORD = Regex("[A-Za-z]+(?:['’][A-Za-z]+)*")

    fun tokenize(text: String): List<Token> =
        WORD.findAll(text).map { Token(it.value, it.range.first, it.range.last + 1) }.toList()

    /** Splits text into non-empty paragraphs, trimming whitespace. */
    fun paragraphs(text: String): List<String> =
        text.replace("\r\n", "\n").replace('\r', '\n')
            .split(Regex("\n\\s*\n|\n"))
            .map { it.trim().replace(Regex("[ \t ]+"), " ") }
            .filter { it.isNotEmpty() }

    private fun isTerminal(c: Char) = c == '.' || c == '!' || c == '?' || c == '…' || c == '\n'

    /** Returns the [start, end) range of the sentence containing [offset]. */
    fun sentenceRange(text: String, offset: Int): IntRange {
        if (text.isEmpty()) return IntRange.EMPTY
        val o = offset.coerceIn(0, text.length - 1)
        var start = o
        while (start > 0) {
            val prev = text[start - 1]
            if (isTerminal(prev) && (start >= text.length || text[start].isWhitespace() || text[start] == '"' || text[start] == '”')) break
            start--
        }
        var end = o
        while (end < text.length) {
            val c = text[end]
            end++
            if (isTerminal(c)) {
                // swallow closing quotes / brackets
                while (end < text.length && text[end] in "\"'”’)]") end++
                if (end >= text.length || text[end].isWhitespace()) break
            }
        }
        while (start < end && text[start].isWhitespace()) start++
        return start until end
    }

    fun sentenceAt(text: String, offset: Int): String {
        val r = sentenceRange(text, offset)
        return if (r.isEmpty()) "" else text.substring(r.first, r.last + 1).trim()
    }

    fun countWords(text: String): Int = WORD.findAll(text).count()
}
