package com.readvocab.core.srs

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

enum class Rating(val value: Int) { Again(1), Hard(2), Good(3), Easy(4) }

/** Memory state of one word. Times are epoch millis; stability is in days. */
data class MemoryState(
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val due: Long = 0L,
    val lastReview: Long = 0L,
    val reps: Int = 0,
    val lapses: Int = 0,
) {
    val isNew: Boolean get() = reps == 0
}

/**
 * FSRS-4.5 scheduler (https://github.com/open-spaced-repetition/fsrs4anki/wiki/The-Algorithm).
 * Intervals are kept fractional (not rounded to whole days) because "reviews" here mostly
 * happen passively while reading.
 */
class Fsrs(
    private val w: DoubleArray = DEFAULT_WEIGHTS,
    private val requestRetention: Double = 0.9,
    private val maximumIntervalDays: Double = 3650.0,
    private val minimumIntervalMinutes: Long = 10,
) {
    init { require(w.size == 17) { "FSRS-4.5 needs 17 weights" } }

    fun retrievability(state: MemoryState, now: Long): Double {
        if (state.isNew || state.stability <= 0) return 0.0
        val elapsedDays = max(0.0, (now - state.lastReview) / DAY_MS)
        return (1 + FACTOR * elapsedDays / state.stability).pow(DECAY)
    }

    fun review(state: MemoryState, rating: Rating, now: Long): MemoryState {
        val g = rating.value
        val (s, d) = if (state.isNew) {
            initStability(g) to initDifficulty(g)
        } else {
            val r = retrievability(state, now)
            val nd = nextDifficulty(state.difficulty, g)
            val ns = if (rating == Rating.Again) forgetStability(state.difficulty, state.stability, r)
            else recallStability(state.difficulty, state.stability, r, g)
            ns to nd
        }
        val intervalDays = nextIntervalDays(s)
        val intervalMs = max(minimumIntervalMinutes * 60_000.0, intervalDays * DAY_MS).roundToLong()
        return MemoryState(
            stability = s,
            difficulty = d,
            due = now + intervalMs,
            lastReview = now,
            reps = state.reps + 1,
            lapses = state.lapses + if (rating == Rating.Again && !state.isNew) 1 else 0,
        )
    }

    fun nextIntervalDays(stability: Double): Double {
        val i = stability / FACTOR * (requestRetention.pow(1 / DECAY) - 1)
        return min(max(i, 0.0), maximumIntervalDays)
    }

    private fun initStability(g: Int) = max(w[g - 1], 0.1)
    private fun initDifficulty(g: Int) = (w[4] - (g - 3) * w[5]).coerceIn(1.0, 10.0)

    private fun nextDifficulty(d: Double, g: Int): Double {
        val next = d - w[6] * (g - 3)
        return (w[7] * initDifficulty(3) + (1 - w[7]) * next).coerceIn(1.0, 10.0)
    }

    private fun recallStability(d: Double, s: Double, r: Double, g: Int): Double {
        val hardPenalty = if (g == 2) w[15] else 1.0
        val easyBonus = if (g == 4) w[16] else 1.0
        return s * (1 + exp(w[8]) * (11 - d) * s.pow(-w[9]) * (exp((1 - r) * w[10]) - 1) * hardPenalty * easyBonus)
    }

    private fun forgetStability(d: Double, s: Double, r: Double): Double =
        min(w[11] * d.pow(-w[12]) * ((s + 1).pow(w[13]) - 1) * exp((1 - r) * w[14]), s)

    companion object {
        const val DAY_MS = 86_400_000.0
        private const val DECAY = -0.5
        private const val FACTOR = 19.0 / 81.0
        val DEFAULT_WEIGHTS = doubleArrayOf(
            0.4872, 1.4003, 3.7145, 13.8206, 5.1618, 1.2298, 0.8975, 0.031,
            1.6474, 0.1367, 1.0461, 2.1072, 0.0793, 0.3246, 1.587, 0.2272, 2.8755,
        )
    }
}
