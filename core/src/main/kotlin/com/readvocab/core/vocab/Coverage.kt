package com.readvocab.core.vocab

import com.readvocab.core.text.ProperNouns
import com.readvocab.core.text.Tokenizer

/**
 * Lexical coverage: the share of running words in a text that the reader already knows.
 * Research on reading in a second language puts comfortable, productive reading at about
 * 95–98% coverage; below 90% most readers struggle to follow.
 */
object Coverage {
    /**
     * Counts each lemma's occurrences in [text], leaving out names (see [ProperNouns]).
     * [lemma] maps a surface form to its lemma.
     */
    fun lemmaCounts(text: String, lemma: (String) -> String): Map<String, Int> {
        val counts = HashMap<String, Int>()
        val paras = Tokenizer.paragraphs(text)
        val names = ProperNouns.find(paras) { paras[it].startsWith("#") }
        paras.forEachIndexed { i, p ->
            for (t in Tokenizer.tokenize(p)) if (t.start !in names[i]) counts.merge(lemma(t.text), 1, Int::plus)
        }
        return counts
    }

    /**
     * @param isKnown true = known, false = unknown, null = not a dictionary word (names, numbers);
     *   those are left out of the measure, as readers do not need to "know" a proper noun.
     * @return the known share in 0..1, or null when the text has no dictionary words.
     */
    fun of(counts: Map<String, Int>, isKnown: (String) -> Boolean?): Double? =
        expected(counts) { lemma -> isKnown(lemma)?.let { if (it) 1.0 else 0.0 } }

    /**
     * Expected known share when each word is known with some probability.
     * @param pKnown 0..1, or null for words left out (names, numbers)
     */
    fun expected(counts: Map<String, Int>, pKnown: (String) -> Double?): Double? {
        var known = 0.0
        var total = 0L
        for ((lemma, n) in counts) {
            val p = pKnown(lemma) ?: continue
            total += n
            known += p * n
        }
        return if (total == 0L) null else known / total
    }

    /**
     * How likely the reader knows [lemma]: their own record wins; otherwise the [curve] from
     * the vocabulary test gives a probability by the word's (family) frequency rank.
     */
    fun knownProbability(rank: Int?, status: WordStatus?, curve: KnownCurve): Double? {
        if (rank == null || rank <= 0) return null
        return when (status) {
            WordStatus.KNOWN, WordStatus.IGNORED, WordStatus.MASTERED, WordStatus.FAMILIAR -> 1.0
            WordStatus.LEARNING -> 0.0
            WordStatus.NEW, null -> curve.probability(rank)
        }
    }

    enum class Level(val label: String) { EASY("轻松"), JUST_RIGHT("合适"), CHALLENGING("有挑战"), HARD("偏难") }

    fun level(coverage: Double): Level = when {
        coverage >= 0.98 -> Level.EASY
        coverage >= 0.95 -> Level.JUST_RIGHT
        coverage >= 0.90 -> Level.CHALLENGING
        else -> Level.HARD
    }

    /**
     * Whether the reader knows [lemma]: their own record wins (only words remembered for a few
     * days or more count as known); otherwise words within the vocabulary-test boundary are known.
     */
    fun isKnown(rank: Int?, status: WordStatus?, knownBoundary: Int): Boolean? {
        if (rank == null || rank <= 0) return null
        return when (status) {
            WordStatus.KNOWN, WordStatus.IGNORED, WordStatus.MASTERED, WordStatus.FAMILIAR -> true
            WordStatus.LEARNING -> false
            WordStatus.NEW, null -> rank <= knownBoundary
        }
    }
}
