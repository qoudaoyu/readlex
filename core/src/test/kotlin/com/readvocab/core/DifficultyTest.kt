package com.readvocab.core

import com.readvocab.core.vocab.Calibration
import com.readvocab.core.vocab.Candidate
import com.readvocab.core.vocab.Coverage
import com.readvocab.core.vocab.Recommender
import com.readvocab.core.vocab.KnownCurve
import com.readvocab.core.vocab.TestItem
import com.readvocab.core.vocab.VocabEstimator
import com.readvocab.core.vocab.WordFamily
import com.readvocab.core.vocab.WordStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DifficultyTest {
    private val ranks = mapOf(
        "defense" to 657, "defence" to 21674, "color" to 697, "colour" to 642, "neighbor" to 2400, "neighbour" to 9000,
        "neighboring" to 4833, "neighbouring" to 31857, "organization" to 300, "organisation" to 9000,
        "center" to 400, "centre" to 1200, "joint" to 2100, "jointly" to 8902, "fortunate" to 5200, "unfortunate" to 7000,
        "unfortunately" to 3000, "happy" to 400, "happiness" to 4000, "careless" to 8000, "care" to 300,
        "live" to 300, "liver" to 4000, "four" to 200, "for" to 10, "acre" to 6000,
    )
    private fun eff(w: String) = WordFamily.effectiveRank(w) { ranks[it] }

    @Test fun familyRanks() {
        assertEquals(657, eff("defence"))
        assertEquals(2400, eff("neighbouring"))
        assertEquals(300, eff("organisation"))
        assertEquals(400, eff("centre"))
        assertEquals(2100, eff("jointly"))
        assertEquals(3000, eff("unfortunately"))
        assertEquals(400, eff("happiness"))
        assertEquals(300, eff("careless"))
        // Not a family: unrelated look-alikes keep their own rank.
        assertEquals(4000, eff("liver"))
        assertEquals(200, eff("four"))
        assertEquals(6000, eff("acre"))
        assertEquals(null, eff("nonword"))
    }

    @Test fun curveFallsSmoothlyAndIgnoresOneSlip() {
        // A reader of about 5,000 words who missed one of six words in the commonest band.
        val bands = listOf(5.0 / 6, 1.0, 1.0, 5.0 / 6, 4.0 / 6, 3.0 / 6, 2.0 / 6, 1.0 / 6, 0.0, 0.0, 0.0, 0.0)
        val curve = KnownCurve.fromTest(bands, falseAlarm = 0.0)
        assertTrue("everyday words stay known: ${curve.probability(200)}", curve.probability(200) > 0.95)
        var last = 1.0
        for (r in listOf(100, 1000, 3000, 5000, 7000, 10000, 20000)) {
            val p = curve.probability(r)
            assertTrue("never rises: $r", p <= last + 1e-9)
            last = p
        }
        assertTrue(curve.probability(5000) in 0.4..0.9)
        assertTrue(curve.probability(20000) < 0.1)
        // Round trip through settings.
        val back = KnownCurve.decode(curve.encode())!!
        assertEquals(curve.probability(6000), back.probability(6000), 0.002)
    }

    @Test fun guessingIsCorrected() {
        val items = VocabEstimator.BANDS.indices.flatMap { b -> (0 until 6).map { TestItem("w$b-$it", b) } } +
            VocabEstimator.PSEUDOWORDS.take(8).map { TestItem(it, TestItem.PSEUDO) }
        // Ticks everything up to band 5 and half the made-up words: an overclaimer.
        val ticked = items.filter { it.band in 0..5 }.map { it.word }.toSet() + VocabEstimator.PSEUDOWORDS.take(4) +
            items.filter { it.band in 6..11 && it.word.endsWith("-0") || it.word.endsWith("-1") }.map { it.word }
        val honest = items.filter { it.band in 0..5 }.map { it.word }.toSet()
        assertEquals(0.5, VocabEstimator.falseAlarm(items, ticked), 1e-9)
        assertTrue(VocabEstimator.estimate(items, ticked) < VocabEstimator.estimate(items, ticked - VocabEstimator.PSEUDOWORDS.toSet()))
        assertEquals(6500, VocabEstimator.knownBoundary(items, honest))
    }

    @Test fun shiftMakesTextsHarder() {
        val c = KnownCurve.fromBoundary(5000)
        assertTrue(c.withShift(0.3).probability(5000) < c.probability(5000))
        assertTrue(c.withShift(-0.3).probability(5000) > c.probability(5000))
    }

    @Test fun expectedCoverage() {
        val curve = KnownCurve.fromBoundary(3000)
        val counts = mapOf("the" to 50, "plateau" to 2, "feud" to 1)
        val rank = mapOf("the" to 1, "plateau" to 8339, "feud" to 10345)
        val cov = Coverage.expected(counts) { Coverage.knownProbability(rank[it], null, curve) }!!
        assertTrue("$cov", cov in 0.93..0.97)
        val learnt = Coverage.expected(counts) { Coverage.knownProbability(rank[it], if (it == "feud") WordStatus.KNOWN else null, curve) }!!
        assertTrue(learnt > cov)
    }

    @Test fun recommendsTheComfortableBandFirst() {
        val now = 10L * 24 * 3600 * 1000
        val h = 3600_000L
        val items = listOf(
            Candidate("easy", "BBC", 0.995, 800, now - h),
            Candidate("right-old", "BBC", 0.966, 900, now - 60 * h),
            Candidate("right-new", "NYT", 0.962, 700, now - 2 * h),
            Candidate("edge", "Economist", 0.951, 1200, now - h),
            Candidate("hard", "Economist", 0.91, 1000, now - h),
            Candidate("teaser", "NYT", 0.965, 120, now - h),
            Candidate("stale", "BBC", 0.965, 900, now - 100 * h),
            Candidate("right-bbc2", "BBC", 0.97, 900, now - 3 * h),
            Candidate("right-bbc3", "BBC", 0.964, 900, now - 4 * h),
        )
        val picked = Recommender.pick(items, now, n = 4).map { it.id }
        assertEquals(listOf("right-bbc3", "right-new", "right-bbc2", "edge"), picked)
        // Nothing in the band: the nearest texts on the easy side still come before hard ones.
        val fallback = Recommender.pick(items.filter { it.id in setOf("easy", "hard") }, now).map { it.id }
        assertEquals(listOf("easy", "hard"), fallback)
    }

    @Test fun feedbackMovesTheEstimate() {
        val curve = KnownCurve.fromBoundary(4000)
        val counts = (1..400).associate { "w$it" to 1 } + mapOf("the" to 3000)
        val rank = { w: String -> if (w == "the") 1 else w.drop(1).toInt() * 30 }
        fun cov(c: KnownCurve) = Coverage.expected(counts) { Coverage.knownProbability(rank(it), null, c) }!!
        val before = cov(curve)
        // Three texts estimated as suitable all felt hard: estimates become harder.
        var shift = 0.0
        repeat(3) { shift = Calibration.adjust(shift, 0.96, Calibration.Felt.HARDER) }
        assertEquals(0.3, shift, 1e-9)
        assertTrue(cov(curve.withShift(shift)) < before)
        // Agreeing answers change nothing; the shift is bounded.
        assertEquals(0.3, Calibration.adjust(0.3, 0.92, Calibration.Felt.HARDER), 1e-9)
        assertEquals(0.3, Calibration.adjust(0.3, 0.96, Calibration.Felt.RIGHT), 1e-9)
        var s = 0.0
        repeat(20) { s = Calibration.adjust(s, 0.97, Calibration.Felt.HARDER) }
        assertEquals(Calibration.LIMIT, s, 1e-9)
    }
}
