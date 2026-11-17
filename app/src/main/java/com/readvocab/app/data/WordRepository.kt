package com.readvocab.app.data

import androidx.room.withTransaction
import com.readvocab.core.srs.Fsrs
import com.readvocab.core.srs.MemoryState
import com.readvocab.core.srs.Rating
import com.readvocab.core.vocab.WordStatus
import kotlinx.coroutines.flow.Flow

/** What happened when a stretch of reading was committed. */
data class ReadingResult(val reviewed: Int, val autoKnown: Int)

/** A word the reader scrolled past: the sentence it appeared in and the form used there. */
data class PassedWord(val lemma: String, val surface: String, val sentence: String, val highlightedAsNew: Boolean)

class WordRepository(private val db: AppDatabase) {
    private val words = db.words()
    private val encounters = db.encounters()
    private val log = db.reviewLog()
    private val fsrs = Fsrs()

    fun observeAll(): Flow<List<WordEntity>> = words.observeAll()
    fun observeByStatus(statuses: List<WordStatus>) = words.observeByStatus(statuses)

    suspend fun get(lemma: String) = words.get(lemma)
    suspend fun getAllStatuses(): Map<String, com.readvocab.core.vocab.WordStatus> = words.statuses().associate { it.lemma to it.status }
    suspend fun encounters(lemma: String) = encounters.forLemma(lemma)
    suspend fun due(limit: Int = 100) = words.due(System.currentTimeMillis(), limit)
    suspend fun countDue() = words.countDue(System.currentTimeMillis())
    suspend fun soonestDue(limit: Int) = words.soonestDue(limit)

    private fun newEntity(lemma: String, now: Long) =
        WordEntity(lemma = lemma, status = WordStatus.NEW, createdAt = now, updatedAt = now)

    private suspend fun addEncounter(lemma: String, surface: String, sentence: String, bookId: Long?, bookTitle: String, now: Long) {
        if (sentence.isBlank() || encounters.countSame(lemma, sentence) > 0) return
        encounters.insert(EncounterEntity(lemma = lemma, surface = surface, sentence = sentence.take(600), bookId = bookId, bookTitle = bookTitle, createdAt = now))
        encounters.trim(lemma, MAX_SENTENCES)
    }

    /**
     * The user tapped a word while reading. Looking a word up does not add it to the word list;
     * but if it is already being learned, needing to look it up again counts as a failed recall.
     * Returns the (possibly updated) record, or null when the word has none.
     */
    suspend fun onLookup(lemma: String): WordEntity? = db.withTransaction {
        val now = System.currentTimeMillis()
        val existing = words.get(lemma) ?: return@withTransaction null
        var updated = existing.copy(lookups = existing.lookups + 1, updatedAt = now)
        if (existing.status.isInReview && now - existing.lastReview > RELOOKUP_GRACE_MS) {
            updated = updated.withMemory(fsrs.review(existing.memory, Rating.Again, now), now)
            log.insert(ReviewLogEntity(lemma = lemma, rating = Rating.Again.value, source = ReviewSource.LOOKUP, at = now))
        }
        words.upsert(updated)
        updated
    }

    /** "加入生词表": starts learning the word and keeps the sentence it was met in for review cards. */
    suspend fun addToLearning(
        lemma: String, surface: String, sentence: String, bookId: Long?, bookTitle: String, aiMeaning: String?,
    ): WordEntity = db.withTransaction {
        val now = System.currentTimeMillis()
        val existing = words.get(lemma) ?: newEntity(lemma, now)
        val updated = if (existing.status.isInReview) existing
        else existing.withMemory(fsrs.review(MemoryState(), Rating.Again, now), now).copy(lookups = existing.lookups + 1)
        words.upsert(updated)
        if (!existing.status.isInReview) logAdd(lemma, now)
        addEncounter(lemma, surface, sentence, bookId, bookTitle, now)
        if (!aiMeaning.isNullOrBlank()) encounters.setAiMeaning(lemma, sentence, aiMeaning)
        updated
    }

    suspend fun setStatus(lemma: String, status: WordStatus) = db.withTransaction {
        val now = System.currentTimeMillis()
        val existing = words.get(lemma) ?: newEntity(lemma, now)
        val updated = when (status) {
            WordStatus.LEARNING -> existing.withMemory(fsrs.review(MemoryState(), Rating.Again, now), now)
            else -> existing.copy(status = status, updatedAt = now)
        }
        words.upsert(updated)
        if (status == WordStatus.LEARNING && !existing.status.isInReview) logAdd(lemma, now)
    }

    private suspend fun logAdd(lemma: String, now: Long) =
        log.insert(ReviewLogEntity(lemma = lemma, rating = Rating.Again.value, source = ReviewSource.ADD, at = now))

    suspend fun saveAiMeaning(lemma: String, sentence: String, meaning: String) = encounters.setAiMeaning(lemma, sentence, meaning)

    suspend fun rate(lemma: String, rating: Rating, source: ReviewSource = ReviewSource.CARD) = db.withTransaction {
        val now = System.currentTimeMillis()
        val existing = words.get(lemma) ?: return@withTransaction
        words.upsert(existing.withMemory(fsrs.review(existing.memory, rating, now), now))
        log.insert(ReviewLogEntity(lemma = lemma, rating = rating.value, source = source, at = now))
    }

    suspend fun delete(lemma: String) = db.withTransaction {
        words.delete(lemma)
        encounters.deleteFor(lemma)
    }

    /**
     * Commits a stretch of reading the user scrolled past.
     *  - Words under review that are due and were read without tapping count as a successful recall.
     *  - New words that were highlighted but not tapped earn a "pass"; after enough passes they become known.
     */
    /**
     * @param earlyReviewAfterMs when set (review stories), a word that is not due yet still counts as
     *   reviewed if at least this long has passed since its last review; FSRS gives a smaller boost for
     *   an early review, so intervals are not inflated.
     */
    suspend fun commitReading(
        passed: Collection<PassedWord>, bookId: Long?, bookTitle: String, autoKnownAfter: Int, earlyReviewAfterMs: Long? = null,
    ): ReadingResult {
        if (passed.isEmpty()) return ReadingResult(0, 0)
        return db.withTransaction {
            val now = System.currentTimeMillis()
            val existing = words.getAll(passed.map { it.lemma }.distinct()).associateBy { it.lemma }
            val updates = ArrayList<WordEntity>()
            var reviewed = 0
            var autoKnown = 0
            for (p in passed) {
                val w = existing[p.lemma]
                if (w != null && w.status.isInReview) {
                    val early = earlyReviewAfterMs != null && now - w.lastReview >= earlyReviewAfterMs
                    if (w.due <= now || early) {
                        updates += w.withMemory(fsrs.review(w.memory, Rating.Good, now), now)
                        log.insert(ReviewLogEntity(lemma = w.lemma, rating = Rating.Good.value, source = ReviewSource.READING, at = now))
                        addEncounter(w.lemma, p.surface, p.sentence, bookId, bookTitle, now)
                        reviewed++
                    }
                } else if (p.highlightedAsNew && autoKnownAfter > 0 && (w == null || w.status == WordStatus.NEW)) {
                    val base = w ?: newEntity(p.lemma, now)
                    val passes = base.passes + 1
                    val status = if (passes >= autoKnownAfter) WordStatus.KNOWN.also { autoKnown++ } else WordStatus.NEW
                    updates += base.copy(passes = passes, status = status, updatedAt = now)
                }
            }
            if (updates.isNotEmpty()) words.upsertAll(updates)
            ReadingResult(reviewed, autoKnown)
        }
    }

    companion object {
        private const val MAX_SENTENCES = 5
        /** Tapping the same word again within this window does not count as another lapse. */
        private const val RELOOKUP_GRACE_MS = 60 * 60 * 1000L
    }
}
