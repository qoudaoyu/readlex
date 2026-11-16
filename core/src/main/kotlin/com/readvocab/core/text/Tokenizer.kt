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

    private fun isTerminal(c: Char) = c == '.' || c == '!' || c == '?' || c == '\u2026'
    private const val CLOSERS = "\"'\u201D\u2019)]"

    /** End offsets (exclusive) of every sentence in [text]; the last one is always text.length. */
    private fun sentenceEnds(text: String): List<Int> {
        val ends = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n') { ends += i + 1; i++; continue }
            if (isTerminal(c)) {
                var j = i + 1
                while (j < text.length && isTerminal(text[j])) j++
                while (j < text.length && text[j] in CLOSERS) j++
                if (j >= text.length) break
                if (text[j].isWhitespace()) {
                    var k = j
                    while (k < text.length && text[k].isWhitespace()) k++
                    // "Really?" she asked. / Mr. smith -> lowercase continuation is the same sentence
                    if (k < text.length && !text[k].isLowerCase()) ends += j
                }
                i = j
                continue
            }
            i++
        }
        if (ends.lastOrNull() != text.length) ends += text.length
        return ends
    }

    /** Splits a paragraph into sentence ranges (inclusive, whitespace-trimmed). */
    fun sentenceRanges(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = 0
        for (end in sentenceEnds(text)) {
            var a = start
            var b = end
            while (a < b && text[a].isWhitespace()) a++
            while (b > a && text[b - 1].isWhitespace()) b--
            if (b > a) out += a until b
            start = end
        }
        return out
    }

    /** Returns the range of the sentence containing [offset]. */
    fun sentenceRange(text: String, offset: Int): IntRange {
        val ranges = sentenceRanges(text)
        if (ranges.isEmpty()) return IntRange.EMPTY
        return ranges.firstOrNull { offset <= it.last } ?: ranges.last()
    }

    fun sentenceAt(text: String, offset: Int): String {
        val r = sentenceRange(text, offset)
        return if (r.isEmpty()) "" else text.substring(r.first, r.last + 1).trim()
    }

    fun countWords(text: String): Int = WORD.findAll(text).count()
}
