package com.readvocab.core.text

import com.readvocab.core.ai.ArticlePhrase

/**
 * Second opinion on the phrases an AI picks from an article. Models over-collect: besides real
 * fixed expressions (engage with, in general) they return plain verb + object chains
 * (creating opportunities), participle clauses (having already run), adjective + noun pairs
 * (thriving habitats) and names (The National Lottery Heritage Fund). Each of those reads fine
 * word by word, so marking it only adds noise.
 *
 * A pick is kept when the dictionary lists it, or when its shape is one a fixed expression takes:
 * a verb or adjective with its particle (engage with, keen on), a phrase opening with a preposition
 * (in general, on their doorstep), or an idiom the AI labelled as such. A term must be in the
 * dictionary (market capitalization is): a noun pile the dictionary does not know
 * (neighbourhood wildlife corridors) is far more often a description than a term.
 */
object PhraseFilter {
    const val MAX = 12

    private val PARTICLES = setOf(
        "about", "across", "after", "against", "along", "apart", "around", "aside", "at", "away", "back", "by",
        "down", "for", "forward", "from", "in", "into", "of", "off", "on", "onto", "out", "over", "through",
        "to", "together", "towards", "under", "up", "upon", "with", "without",
    )
    private val OPENING_PREPOSITIONS = setOf(
        "at", "by", "for", "from", "in", "on", "out", "under", "with", "without", "beyond", "behind", "off", "to",
        "within", "against", "ahead", "all", "as", "per",
    )
    private val ARTICLES = setOf("a", "an", "the")
    private val AUXILIARIES = setOf("having", "being", "been", "is", "are", "was", "were", "has", "have", "had", "will", "would")
    private val IDIOM_TYPES = setOf("idiom")

    /** [inDictionary] tells whether the phrase table lists a dictionary form. */
    fun keep(p: ArticlePhrase, inDictionary: (String) -> Boolean): Boolean {
        val words = p.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 2 || words.size > 6 || p.meaning.isBlank()) return false
        val lower = words.map { it.lowercase().trim(',', '.', ';', ':', '"', '\'', '“', '”', '‘', '’') }
        // A name: two or more capitalised words, or a capitalised word after an article (The National Lottery …).
        val capitals = words.count { it.first().isUpperCase() }
        if (capitals >= 2 || (capitals >= 1 && words.drop(1).any { it.first().isUpperCase() })) return false
        val dict = listOf(p.phrase, p.text).map { it.trim().lowercase() }.any { it.contains(' ') && inDictionary(it) }
        if (dict) return true
        // Grammar around a verb, not an expression: having already run, is being built.
        if (lower.first() in AUXILIARIES) return false
        val type = p.type.trim().lowercase()
        val first = lower.first()
        val last = lower.last()
        return when {
            // engage with, sign up, keen on: a word and its particle. "creating opportunities" ends in a noun.
            last in PARTICLES && first !in ARTICLES -> !first.endsWith("ing") || words.size == 2
            // in general, on their doorstep, at stake
            first in OPENING_PREPOSITIONS -> true
            // Anything else needs the AI to call it an idiom, and must not be a participle + noun
            // (creating opportunities, thriving habitats, getting people involved).
            type in IDIOM_TYPES -> !(first.endsWith("ing") && first.length > 5)
            else -> false
        }
    }

    fun filter(list: List<ArticlePhrase>, inDictionary: (String) -> Boolean): List<ArticlePhrase> =
        list.map { trimToDictionary(it, inDictionary) }.filter { keep(it, inDictionary) }
            .distinctBy { it.text.trim().lowercase() }.take(MAX)

    /**
     * When the AI took in ordinary words around a dictionary phrase ("act as stepping stones"),
     * keep only the phrase ("stepping stones", listed as "stepping stone").
     */
    fun trimToDictionary(p: ArticlePhrase, inDictionary: (String) -> Boolean): ArticlePhrase {
        val words = p.text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.size < 3) return p
        val whole = listOf(p.phrase, p.text).map { it.trim().lowercase() }
        if (whole.any { inDictionary(it) }) return p
        for (len in words.size - 1 downTo 2) {
            for (from in 0..words.size - len) {
                val part = words.subList(from, from + len)
                val key = part.joinToString(" ").lowercase()
                val singular = (part.dropLast(1) + part.last().removeSuffix("s")).joinToString(" ").lowercase()
                val form = listOf(key, singular).firstOrNull(inDictionary) ?: continue
                return p.copy(text = part.joinToString(" "), phrase = form)
            }
        }
        return p
    }
}
