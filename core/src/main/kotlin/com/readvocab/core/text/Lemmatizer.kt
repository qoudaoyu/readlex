package com.readvocab.core.text

/**
 * Maps an inflected surface form to its dictionary lemma (ran -> run, studies -> study).
 *
 * [formToLemma] is the authoritative table built from ECDICT (exchange field + lemma list).
 * [isWord] tells whether a lowercase string is a dictionary headword; it is used by the
 * rule-based fallback for forms the table does not cover.
 */
class Lemmatizer(
    private val formToLemma: (String) -> String?,
    private val isWord: (String) -> Boolean,
    /** Frequency rank of a headword (0 or null = unknown); lets a common word keep its own entry. */
    private val rank: (String) -> Int? = { null },
) {
    fun normalize(surface: String): String {
        var w = surface.lowercase().replace('’', '\'')
        if (w.endsWith("'s")) w = w.dropLast(2)
        else if (w.endsWith("s'")) w = w.dropLast(1)
        return w
    }

    fun lemma(surface: String): String {
        val w = normalize(surface)
        formToLemma(w)?.let { lemma ->
            // "according" is far more common than "accord", "mapped" far rarer than "map":
            // a form much more common than its lemma is a word in its own right.
            val own = rank(w) ?: 0
            val base = rank(lemma) ?: 0
            return if (own > 0 && base > 0 && own * 2 < base) w else lemma
        }
        if (isWord(w)) return w
        for (c in candidates(w)) if (isWord(c)) return formToLemma(c) ?: c
        return w
    }

    private fun candidates(w: String): List<String> {
        val out = ArrayList<String>(8)
        fun undouble(stem: String) {
            if (stem.length >= 3 && stem[stem.length - 1] == stem[stem.length - 2]) out += stem.dropLast(1)
        }
        when {
            w.endsWith("ies") && w.length > 4 -> out += w.dropLast(3) + "y"
            w.endsWith("ied") && w.length > 4 -> out += w.dropLast(3) + "y"
            w.endsWith("ier") && w.length > 4 -> out += w.dropLast(3) + "y"
            w.endsWith("iest") && w.length > 5 -> out += w.dropLast(4) + "y"
        }
        if (w.endsWith("ing") && w.length > 4) {
            val stem = w.dropLast(3); out += stem; out += stem + "e"; undouble(stem)
        }
        if (w.endsWith("ed") && w.length > 3) {
            val stem = w.dropLast(2); out += stem; out += w.dropLast(1); undouble(stem)
        }
        if (w.endsWith("es") && w.length > 3) out += w.dropLast(2)
        if (w.endsWith("s") && !w.endsWith("ss") && w.length > 2) out += w.dropLast(1)
        if (w.endsWith("est") && w.length > 4) { val s = w.dropLast(3); out += s; out += s + "e"; undouble(s) }
        if (w.endsWith("er") && w.length > 3) { val s = w.dropLast(2); out += s; out += s + "e"; undouble(s) }
        if (w.endsWith("ly") && w.length > 4) out += w.dropLast(2)
        return out
    }
}
