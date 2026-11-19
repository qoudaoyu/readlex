package com.readvocab.core.text

/** A dictionary phrase: [key] is its words as lemmas, with #poss, #refl, #sb, #sth for placeholders. */
data class PhraseEntry(val phrase: String, val key: List<String>, val kind: Int) {
    companion object {
        const val GENERAL = 1
        /** An economics or law term (market capitalization). */
        const val TERM = 2
    }
}

/** A phrase found in a text: characters [start, end), tokens [firstToken, lastToken]. */
data class PhraseMatch(val start: Int, val end: Int, val phrase: String, val kind: Int, val firstToken: Int, val lastToken: Int)

/**
 * Finds dictionary phrases in a paragraph by their lemmas, so "caught up with" matches
 * "catch up with" and "kept their word" matches "keep one's word". Words of a phrase must follow
 * each other with only spaces between them (no punctuation), except that a two-word phrasal verb
 * may have an object pronoun in the middle (take it off), and placeholders stand for one to three words.
 * Where phrases overlap, the one starting first wins, then the longest.
 */
object PhraseMatcher {
    private val POSSESSIVE = setOf("my", "your", "his", "her", "its", "our", "their", "one")
    private val REFLEXIVE = setOf("myself", "yourself", "himself", "herself", "itself", "ourselves", "yourselves", "themselves", "oneself")
    private val OBJECT_PRONOUN = setOf("it", "them", "him", "her", "me", "us", "you", "this", "that")
    private val PARTICLES = setOf("up", "down", "out", "off", "in", "on", "away", "back", "over", "around", "about", "through", "aside", "apart")

    /**
     * @param lemmas the lemma of each token, lowercase
     * @param candidates the phrases whose first word has the given lemma
     */
    fun match(text: String, tokens: List<Token>, lemmas: List<String>, candidates: (String) -> List<PhraseEntry>): List<PhraseMatch> {
        val out = ArrayList<PhraseMatch>()
        var i = 0
        while (i < tokens.size) {
            var best: PhraseMatch? = null
            for (p in candidates(lemmas[i])) {
                val last = matchAt(text, tokens, lemmas, i, p.key) ?: continue
                if (best == null || last > best.lastToken) best = PhraseMatch(tokens[i].start, tokens[last].end, p.phrase, p.kind, i, last)
            }
            if (best != null) {
                out += best
                i = best.lastToken + 1
            } else {
                i++
            }
        }
        return out
    }

    /** Index of the last token of [key] matched from token [from], or null. */
    private fun matchAt(text: String, tokens: List<Token>, lemmas: List<String>, from: Int, key: List<String>): Int? {
        if (key.isEmpty() || lemmas[from] != key[0]) return null
        fun joined(a: Int, b: Int) = text.substring(tokens[a].end, tokens[b].start).isBlank()
        fun go(k: Int, t: Int): Int? { // key[k] to match at token t; returns last matched token
            if (k == key.size) return t - 1
            if (t >= tokens.size || !joined(t - 1, t)) return null
            val want = key[k]
            val word = tokens[t].text.lowercase().replace('’', '\'')
            return when (want) {
                "#poss" -> if (lemmas[t] in POSSESSIVE || word.endsWith("'s")) go(k + 1, t + 1) else null
                "#refl" -> if (word in REFLEXIVE) go(k + 1, t + 1) else null
                "#sb", "#sth" -> (1..3).firstNotNullOfOrNull { n ->
                    if (t + n - 1 >= tokens.size || (n > 1 && !joined(t + n - 2, t + n - 1))) null else go(k + 1, t + n)
                }
                else -> when {
                    lemmas[t] == want -> go(k + 1, t + 1)
                    // take it off, turn them down
                    k == 1 && key.size == 2 && want in PARTICLES && word in OBJECT_PRONOUN &&
                        t + 1 < tokens.size && joined(t, t + 1) && lemmas[t + 1] == want -> t + 1
                    else -> null
                }
            }
        }
        return go(1, from + 1)
    }
}
