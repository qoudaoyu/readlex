package com.readvocab.app.data

import com.readvocab.core.vocab.WordStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One calendar day of activity. */
data class DayStat(val day: LocalDate, val reviews: Int, val added: Int, val wordsRead: Int, val seconds: Long) {
    val active: Boolean get() = reviews > 0 || wordsRead > 0
}

data class Stats(
    val today: DayStat,
    /** Consecutive days with reading or reviews, ending today (or yesterday, if today is still empty). */
    val streak: Int,
    val dueNow: Int,
    /** Last [StatsRepository.HISTORY_DAYS] days, oldest first, today last. */
    val history: List<DayStat>,
    /** Words coming due on each of the next 7 days; index 0 includes everything overdue. */
    val forecast: List<Int>,
    val learning: Int,
    val familiar: Int,
    val mastered: Int,
    val known: Int,
    val totalReviews: Int,
    val totalWordsRead: Int,
) {
    val inList: Int get() = learning + familiar + mastered
}

class StatsRepository(
    private val db: AppDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val reading = db.dailyReading()

    fun today(): LocalDate = dayOf(clock())
    private fun dayOf(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate()

    /** Records reading done in the reader: words scrolled past and active seconds. */
    suspend fun addReading(words: Int, seconds: Long) {
        if (words <= 0 && seconds <= 0) return
        val day = today().toEpochDay()
        reading.ensure(day)
        reading.add(day, words.coerceAtLeast(0), seconds.coerceAtLeast(0))
    }

    fun observe(): Flow<Stats> {
        val today = today()
        // Enough history for a long streak; charts only show the last HISTORY_DAYS.
        val from = today.minusDays(STREAK_LOOKBACK_DAYS)
        val fromMs = from.atStartOfDay(zone()).toInstant().toEpochMilli()
        return combine(
            db.words().observeAll(),
            db.reviewLog().observeSince(fromMs),
            reading.observeFrom(from.toEpochDay()),
            db.reviewLog().observeTotalReviews(),
            reading.observeTotalWords(),
        ) { words, log, read, totalReviews, totalRead ->
            build(words, log, read, totalReviews, totalRead)
        }
    }

    internal fun build(
        words: List<WordEntity>, log: List<LogPoint>, read: List<DailyReadingEntity>, totalReviews: Int, totalRead: Int,
    ): Stats {
        val now = clock()
        val today = dayOf(now)
        val reviews = HashMap<LocalDate, Int>()
        val added = HashMap<LocalDate, Int>()
        for (p in log) {
            val d = dayOf(p.at)
            if (p.source == ReviewSource.ADD) added.merge(d, 1, Int::plus) else reviews.merge(d, 1, Int::plus)
        }
        val readByDay = read.associateBy { LocalDate.ofEpochDay(it.day) }
        fun stat(d: LocalDate) = DayStat(d, reviews[d] ?: 0, added[d] ?: 0, readByDay[d]?.words ?: 0, readByDay[d]?.seconds ?: 0)

        var streak = 0
        var d = if (stat(today).active) today else today.minusDays(1)
        while (stat(d).active && streak <= STREAK_LOOKBACK_DAYS) { streak++; d = d.minusDays(1) }

        val inReview = words.filter { it.status.isInReview }
        val forecast = IntArray(7)
        for (w in inReview) {
            val offset = if (w.due <= now) 0L else today.until(dayOf(w.due), java.time.temporal.ChronoUnit.DAYS)
            if (offset in 0..6) forecast[offset.toInt()]++
        }
        return Stats(
            today = stat(today),
            streak = streak,
            dueNow = inReview.count { it.due <= now },
            history = (HISTORY_DAYS - 1 downTo 0).map { stat(today.minusDays(it.toLong())) },
            forecast = forecast.toList(),
            learning = words.count { it.status == WordStatus.LEARNING },
            familiar = words.count { it.status == WordStatus.FAMILIAR },
            mastered = words.count { it.status == WordStatus.MASTERED },
            known = words.count { it.status == WordStatus.KNOWN },
            totalReviews = totalReviews,
            totalWordsRead = totalRead,
        )
    }

    companion object {
        const val HISTORY_DAYS = 30
        const val STREAK_LOOKBACK_DAYS = 400L
    }
}
