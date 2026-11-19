package com.readvocab.core.vocab

import kotlin.math.abs

/** A news article that has been read in the background and measured. */
data class Candidate(val id: String, val source: String, val coverage: Double, val words: Int, val publishedAt: Long)

/**
 * Picks the articles best suited to the reader: those whose coverage falls in the 95–98% band
 * where reading is comfortable yet still teaches new words, nearest its middle first, fresher
 * first, and from more than one source.
 */
object Recommender {
    const val LOW = 0.95
    const val HIGH = 0.98
    const val TARGET = (LOW + HIGH) / 2
    /** Fewer words than this is too small a sample to judge a text by (about ±1–2 points of coverage). */
    const val MIN_WORDS = 300
    const val MAX_AGE_MS = 3L * 24 * 3600 * 1000

    fun pick(candidates: List<Candidate>, now: Long, n: Int = 3, perSource: Int = 2): List<Candidate> {
        val ranked = candidates
            .filter { it.words >= MIN_WORDS && it.coverage >= 0.90 && now - it.publishedAt <= MAX_AGE_MS }
            .sortedBy { score(it, now) }
        val out = ArrayList<Candidate>()
        val bySource = HashMap<String, Int>()
        for (c in ranked) {
            if (out.size >= n) break
            if ((bySource[c.source] ?: 0) >= perSource) continue
            out += c
            bySource.merge(c.source, 1, Int::plus)
        }
        return out
    }

    /** Lower is better. Being outside the band outweighs everything; within it, closeness to the middle and freshness. */
    internal fun score(c: Candidate, now: Long): Double {
        val outside = when {
            c.coverage < LOW -> LOW - c.coverage
            c.coverage > HIGH -> c.coverage - HIGH
            else -> 0.0
        }
        val ageDays = ((now - c.publishedAt).coerceAtLeast(0)) / (24.0 * 3600 * 1000)
        val long = if (c.words > 2500) 0.004 else 0.0
        return outside * 10 + abs(c.coverage - TARGET) + ageDays * 0.004 + long
    }
}
