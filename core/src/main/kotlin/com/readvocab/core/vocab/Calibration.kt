package com.readvocab.core.vocab

/**
 * Corrects the difficulty estimate with the reader's own judgement after reading. When a text
 * estimated as suitable felt hard, every estimate moves harder (and the other way round), a step
 * at a time, so a few consistent answers fix a systematic error while one odd text barely matters.
 */
object Calibration {
    enum class Felt(val label: String) { EASIER("偏易"), RIGHT("正好"), HARDER("偏难") }

    const val STEP = 0.1
    const val LIMIT = 0.8

    /** @return the new [KnownCurve.shift] after the reader said how a text estimated at [predicted] felt. */
    fun adjust(shift: Double, predicted: Double, felt: Felt): Double {
        val level = Coverage.level(predicted)
        val delta = when (felt) {
            Felt.HARDER -> when (level) {
                Coverage.Level.EASY, Coverage.Level.JUST_RIGHT -> STEP
                else -> 0.0
            }
            Felt.EASIER -> when (level) {
                Coverage.Level.EASY -> 0.0
                else -> -STEP
            }
            Felt.RIGHT -> when (level) {
                Coverage.Level.EASY -> STEP / 2
                Coverage.Level.JUST_RIGHT -> 0.0
                else -> -STEP / 2
            }
        }
        return (shift + delta).coerceIn(-LIMIT, LIMIT)
    }
}
