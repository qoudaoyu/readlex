package com.readvocab.core.vocab

import kotlin.math.exp
import kotlin.math.ln

/**
 * How likely the reader is to know a word they have no record of, by its frequency rank.
 *
 * Built from the vocabulary test: the share of sample words the reader knew in each frequency
 * band, corrected for guessing with the made-up words in the test, then smoothed so it never rises
 * with rank. Between bands it is interpolated on a log scale. This replaces a hard cut-off, under
 * which a reader "knew" every word ranked 5,000 and none ranked 5,001.
 *
 * [shift] moves the curve after the reader says texts felt harder (positive) or easier (negative)
 * than estimated: the probability for rank r becomes the old one for rank r·e^shift.
 */
class KnownCurve(val points: List<Pair<Int, Double>>, val shift: Double = 0.0) {
    init { require(points.isNotEmpty()) }

    fun probability(rank: Int): Double {
        val r = rank * exp(shift)
        val x = ln(r.coerceAtLeast(1.0))
        if (r <= points.first().first) {
            // The very commonest words (the, and, of) are known by anyone who can use this app.
            val (r0, p0) = points.first()
            return 1.0 + (p0 - 1.0) * (x / ln(r0.toDouble()))
        }
        if (r >= points.last().first) return points.last().second
        val i = points.indexOfFirst { it.first >= r }
        val (r0, p0) = points[i - 1]
        val (r1, p1) = points[i]
        val t = (x - ln(r0.toDouble())) / (ln(r1.toDouble()) - ln(r0.toDouble()))
        return p0 + (p1 - p0) * t
    }

    fun withShift(s: Double) = KnownCurve(points, s)

    /** "rank:p;rank:p" for settings. */
    fun encode(): String = points.joinToString(";") { "${it.first}:${"%.3f".format(java.util.Locale.ROOT, it.second)}" }

    companion object {
        fun decode(s: String, shift: Double = 0.0): KnownCurve? = runCatching {
            val pts = s.split(';').filter { it.isNotBlank() }.map { p -> p.split(':').let { it[0].toInt() to it[1].toDouble() } }
            if (pts.isEmpty()) null else KnownCurve(pts, shift)
        }.getOrNull()

        /** No test taken yet: a smooth fall around [boundary], the rank where half the words are known. */
        fun fromBoundary(boundary: Int, shift: Double = 0.0) =
            KnownCurve(VocabEstimator.BANDS.map { mid(it) to prior(mid(it), boundary.toDouble()) }, shift)

        /**
         * @param bandKnown for each test band (see [VocabEstimator.BANDS]), the share of its words ticked
         * @param falseAlarm the share of made-up words ticked, the reader's rate of guessing
         * @param perBand how many words each band had in the test
         */
        fun fromTest(bandKnown: List<Double>, falseAlarm: Double, perBand: Int = VocabEstimator.WORDS_PER_BAND, shift: Double = 0.0): KnownCurve {
            val f = falseAlarm.coerceIn(0.0, 0.9)
            val corrected = bandKnown.map { ((it - f) / (1 - f)).coerceIn(0.0, 1.0) }
            // Six words a band is a small sample: one slip in the commonest band would otherwise
            // mark a thousand everyday words as unknown. Each band is weighed against what the
            // overall vocabulary size predicts for it, with that prediction counting as many words.
            val size = VocabEstimator.BANDS.zip(corrected).sumOf { (band, h) -> h * band.size }.coerceAtLeast(500.0)
            val posterior = VocabEstimator.BANDS.zip(corrected).map { (band, h) ->
                (h * perBand + prior(mid(band), size) * PRIOR_WEIGHT) / (perBand + PRIOR_WEIGHT)
            }
            val pts = VocabEstimator.BANDS.zip(decreasing(posterior)).map { (band, p) -> mid(band) to p.coerceIn(0.01, 1.0) }
            return KnownCurve(pts, shift)
        }

        private const val PRIOR_WEIGHT = 6.0

        private fun mid(band: Band) = (band.from + band.to) / 2

        /** Logistic fall on a log scale: half the words at [size] are known, few at twice the size. */
        internal fun prior(rank: Int, size: Double): Double = 1 / (1 + exp((ln(rank.toDouble()) - ln(size)) / 0.35))

        /** Pool-adjacent-violators: the closest non-increasing sequence (least squares). */
        internal fun decreasing(v: List<Double>): List<Double> {
            val means = ArrayList<Double>()
            val sizes = ArrayList<Int>()
            for (x in v) {
                means += x; sizes += 1
                while (means.size > 1 && means[means.size - 2] < means.last()) {
                    val n = sizes[sizes.size - 2] + sizes.last()
                    val m = (means[means.size - 2] * sizes[sizes.size - 2] + means.last() * sizes.last()) / n
                    means.removeAt(means.lastIndex); sizes.removeAt(sizes.lastIndex)
                    means[means.lastIndex] = m; sizes[sizes.lastIndex] = n
                }
            }
            return means.zip(sizes).flatMap { (m, n) -> List(n) { m } }
        }
    }
}
