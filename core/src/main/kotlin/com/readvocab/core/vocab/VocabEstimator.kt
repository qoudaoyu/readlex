package com.readvocab.core.vocab

import kotlin.math.roundToInt

/** A frequency band [from, to] (inclusive ranks) used by the vocabulary-size test. */
data class Band(val from: Int, val to: Int) {
    val size: Int get() = to - from + 1
}

data class TestItem(val word: String, val band: Int)

object VocabEstimator {
    val BANDS = listOf(
        Band(1, 1000), Band(1001, 2000), Band(2001, 3000), Band(3001, 4000),
        Band(4001, 5000), Band(5001, 6500), Band(6501, 8000), Band(8001, 10000),
        Band(10001, 12500), Band(12501, 15000), Band(15001, 20000), Band(20001, 30000),
    )
    const val WORDS_PER_BAND = 6

    /**
     * Estimates vocabulary size from per-band answers.
     * @param known for each item, whether the user said they know it
     */
    fun estimate(items: List<TestItem>, known: Set<String>): Int {
        var total = 0.0
        BANDS.forEachIndexed { i, band ->
            val inBand = items.filter { it.band == i }
            if (inBand.isEmpty()) return@forEachIndexed
            val ratio = inBand.count { it.word in known }.toDouble() / inBand.size
            total += ratio * band.size
        }
        return total.roundToInt()
    }

    /**
     * The rank below which words are treated as known. Uses the last band where the user
     * knew at least half the sample, so a few lucky guesses at the top do not inflate it.
     */
    fun knownBoundary(items: List<TestItem>, known: Set<String>): Int {
        var boundary = 0
        BANDS.forEachIndexed { i, band ->
            val inBand = items.filter { it.band == i }
            if (inBand.isEmpty()) return@forEachIndexed
            val ratio = inBand.count { it.word in known }.toDouble() / inBand.size
            if (ratio >= 0.5) boundary = band.to else return boundary
        }
        return boundary
    }
}
