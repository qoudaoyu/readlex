package com.readvocab.core.vocab

import kotlin.math.roundToInt

/** A frequency band [from, to] (inclusive ranks) used by the vocabulary-size test. */
data class Band(val from: Int, val to: Int) {
    val size: Int get() = to - from + 1
}

/** A word in the vocabulary test; [band] is an index into [VocabEstimator.BANDS], or [PSEUDO] for a made-up word. */
data class TestItem(val word: String, val band: Int) {
    companion object { const val PSEUDO = -1 }
}

object VocabEstimator {
    val BANDS = listOf(
        Band(1, 1000), Band(1001, 2000), Band(2001, 3000), Band(3001, 4000),
        Band(4001, 5000), Band(5001, 6500), Band(6501, 8000), Band(8001, 10000),
        Band(10001, 12500), Band(12501, 15000), Band(15001, 20000), Band(20001, 30000),
    )
    const val WORDS_PER_BAND = 6

    /**
     * Made-up but English-looking words, mixed into the test. Ticking them shows how often the reader
     * ticks words they do not really know, and every band's score is corrected by that rate
     * (the standard correction of the yes/no vocabulary test).
     */
    val PSEUDOWORDS = listOf(
        "plimble", "drollop", "sprinch", "contrapise", "flendery", "obsolate",
        "traffage", "wendacle", "pastulate", "vorsitude", "crembling", "umbrous",
    )
    const val PSEUDO_PER_TEST = 8

    /** Share of each band's words the reader ticked. */
    fun bandKnown(items: List<TestItem>, known: Set<String>): List<Double> = BANDS.indices.map { i ->
        val inBand = items.filter { it.band == i }
        if (inBand.isEmpty()) 0.0 else inBand.count { it.word in known }.toDouble() / inBand.size
    }

    /** Share of the made-up words the reader ticked. */
    fun falseAlarm(items: List<TestItem>, known: Set<String>): Double {
        val pseudo = items.filter { it.band == TestItem.PSEUDO }
        return if (pseudo.isEmpty()) 0.0 else pseudo.count { it.word in known }.toDouble() / pseudo.size
    }

    private fun corrected(items: List<TestItem>, known: Set<String>): List<Double?> {
        val f = falseAlarm(items, known).coerceAtMost(0.9)
        return BANDS.indices.map { i ->
            val inBand = items.filter { it.band == i }
            if (inBand.isEmpty()) null
            else ((inBand.count { it.word in known }.toDouble() / inBand.size - f) / (1 - f)).coerceIn(0.0, 1.0)
        }
    }

    /**
     * Estimates vocabulary size from per-band answers, corrected for ticked made-up words.
     * @param known for each item, whether the user said they know it
     */
    fun estimate(items: List<TestItem>, known: Set<String>): Int {
        var total = 0.0
        corrected(items, known).forEachIndexed { i, ratio -> if (ratio != null) total += ratio * BANDS[i].size }
        return total.roundToInt()
    }

    /**
     * The rank below which words are treated as known. Uses the last band where the user
     * knew at least half the sample, so a few lucky guesses at the top do not inflate it.
     */
    fun knownBoundary(items: List<TestItem>, known: Set<String>): Int {
        var boundary = 0
        corrected(items, known).forEachIndexed { i, ratio ->
            if (ratio == null) return@forEachIndexed
            if (ratio >= 0.5) boundary = BANDS[i].to else return boundary
        }
        return boundary
    }
}
