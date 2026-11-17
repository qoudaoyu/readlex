package com.readvocab.core.text

/**
 * Finds the names (people, places, organisations) in a text, so the reader does not flag them as
 * new words: "Kerch" or "Ukraine" are not vocabulary to learn, even when the dictionary lists them.
 *
 * English capitalises names everywhere but also capitalises the first word of a sentence and,
 * in many headlines, every word. So the text itself is the evidence:
 * - a capitalised word in mid-sentence is a name, unless the same word also appears in lower case;
 * - a capitalised word at the start of a sentence, or in a headline, is a name only if it also
 *   appears capitalised in mid-sentence and never in lower case;
 * - an all-capital word of two or more letters (NATO, BBC) is an abbreviation, unless it also
 *   appears in lower case.
 */
object ProperNouns {
    /**
     * @param paragraphs the text, one paragraph each
     * @param titleCase true for a paragraph whose words may all be capitalised (a headline)
     * @return for each paragraph, the start offsets of the tokens that are names
     */
    fun find(paragraphs: List<String>, titleCase: (Int) -> Boolean = { false }): List<Set<Int>> {
        val lowerSeen = HashSet<String>()
        val midCaps = HashSet<String>()
        val parsed = paragraphs.mapIndexed { i, p ->
            val tokens = Tokenizer.tokenize(p)
            val firsts = sentenceFirsts(p, tokens)
            if (!titleCase(i)) {
                tokens.forEachIndexed { j, t ->
                    val key = t.text.lowercase()
                    if (t.text[0].isLowerCase()) lowerSeen += key
                    else if (j !in firsts && !allCaps(t.text)) midCaps += key
                }
            }
            Triple(tokens, firsts, titleCase(i))
        }
        return parsed.map { (tokens, firsts, title) ->
            val out = HashSet<Int>()
            tokens.forEachIndexed { j, t ->
                val key = t.text.lowercase()
                val name = when {
                    t.text[0].isLowerCase() || key in lowerSeen -> false
                    allCaps(t.text) -> true
                    title || j in firsts -> key in midCaps
                    else -> true
                }
                if (name) out += t.start
            }
            out
        }
    }

    private fun allCaps(s: String) = s.length >= 2 && s.all { !it.isLetter() || it.isUpperCase() }

    /** Indexes of the tokens that open a sentence (or a list item, which starts after its bullet). */
    private fun sentenceFirsts(text: String, tokens: List<Token>): Set<Int> {
        val out = HashSet<Int>()
        for (r in Tokenizer.sentenceRanges(text)) {
            val j = tokens.indexOfFirst { it.start >= r.first }
            if (j >= 0) out += j
        }
        // After a colon or an opening quote mark a new clause starts with a capital too.
        tokens.forEachIndexed { j, t ->
            val before = text.substring(0, t.start).trimEnd()
            if (before.endsWith(":") || before.endsWith("“") || before.endsWith("\"")) out += j
        }
        return out
    }
}
