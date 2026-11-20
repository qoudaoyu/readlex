package com.readvocab.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.readvocab.core.vocab.WordStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY CASE WHEN lastReadAt > 0 THEN lastReadAt ELSE addedAt END DESC")
    fun observeAll(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun get(id: Long): BookEntity?

    @Insert
    suspend fun insert(book: BookEntity): Long

    @Insert
    suspend fun insertChapters(chapters: List<ChapterEntity>)

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY idx")
    suspend fun chapters(bookId: Long): List<ChapterEntity>

    @Query("UPDATE books SET chapterIndex = :chapter, paragraphIndex = :paragraph, lastReadAt = :now WHERE id = :id")
    suspend fun updateProgress(id: Long, chapter: Int, paragraph: Int, now: Long)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM chapters WHERE bookId = :id")
    suspend fun deleteChapters(id: Long)

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int

    @Query("SELECT * FROM books WHERE sourceUrl = :url LIMIT 1")
    suspend fun bySourceUrl(url: String): BookEntity?
}

@Dao
interface FeedDao {
    @Query("SELECT * FROM feeds ORDER BY position, title")
    fun observeFeeds(): Flow<List<FeedEntity>>

    @Query("SELECT * FROM feeds WHERE enabled = 1 ORDER BY position")
    suspend fun enabledFeeds(): List<FeedEntity>

    @Query("SELECT COUNT(*) FROM feeds")
    suspend fun countFeeds(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFeeds(feeds: List<FeedEntity>)

    @Upsert
    suspend fun upsertFeed(feed: FeedEntity)

    @Query("DELETE FROM feeds WHERE url = :url")
    suspend fun deleteFeed(url: String)

    @Query("UPDATE feeds SET enabled = :enabled WHERE url = :url")
    suspend fun setEnabled(url: String, enabled: Boolean)

    @Query("UPDATE feeds SET lastFetchedAt = :at, lastError = :error WHERE url = :url")
    suspend fun markFetched(url: String, at: Long, error: String?)

    /** Newest items from enabled feeds. */
    @Query("SELECT i.* FROM feed_items i JOIN feeds f ON f.url = i.feedUrl WHERE f.enabled = 1 ORDER BY i.publishedAt DESC, i.fetchedAt DESC LIMIT :limit")
    fun observeItems(limit: Int = 300): Flow<List<FeedItemEntity>>

    @Upsert
    suspend fun upsertItems(items: List<FeedItemEntity>)

    @Query("DELETE FROM feed_items WHERE feedUrl = :url")
    suspend fun deleteItems(url: String)

    /** Keeps the cache small: drop items older than [before]. */
    @Query("DELETE FROM feed_items WHERE publishedAt < :before AND fetchedAt < :before")
    suspend fun prune(before: Long)
}

@Dao
interface ArticleCheckDao {
    @Query("SELECT * FROM article_checks")
    fun observeAll(): Flow<List<ArticleCheckEntity>>

    /**
     * Recent items of enabled feeds not checked yet, newest first; a failed check is retried after
     * [retryBefore], a short (paywalled) one after [shortRetryBefore], in case the reader has signed in since.
     */
    @Query(
        "SELECT i.* FROM feed_items i JOIN feeds f ON f.url = i.feedUrl LEFT JOIN article_checks c ON c.link = i.link " +
            "WHERE f.enabled = 1 AND i.publishedAt >= :since AND (c.link IS NULL " +
            "OR (c.status = 'FAILED' AND c.checkedAt < :retryBefore) OR (c.status = 'SHORT' AND c.checkedAt < :shortRetryBefore)) " +
            "ORDER BY i.publishedAt DESC LIMIT :limit",
    )
    suspend fun pending(since: Long, retryBefore: Long, shortRetryBefore: Long, limit: Int): List<FeedItemEntity>

    @Upsert
    suspend fun upsert(check: ArticleCheckEntity)

    @Query("DELETE FROM article_checks WHERE link NOT IN (SELECT link FROM feed_items)")
    suspend fun pruneOrphans()
}

@Dao
interface WordDao {
    @Query("SELECT * FROM words")
    fun observeAll(): Flow<List<WordEntity>>

    @Query("SELECT * FROM words WHERE lemma = :lemma")
    suspend fun get(lemma: String): WordEntity?

    @Query("SELECT * FROM words WHERE lemma IN (:lemmas)")
    suspend fun getAll(lemmas: List<String>): List<WordEntity>

    @Upsert
    suspend fun upsert(word: WordEntity)

    @Upsert
    suspend fun upsertAll(words: List<WordEntity>)

    @Query("SELECT * FROM words WHERE status IN (:statuses) AND due <= :now ORDER BY due LIMIT :limit")
    suspend fun due(now: Long, limit: Int, statuses: List<WordStatus> = REVIEW_STATUSES): List<WordEntity>

    /** Words for a review story: overdue first, then the ones coming due soonest; lapses break ties. */
    @Query("SELECT * FROM words WHERE status IN (:statuses) ORDER BY due ASC, lapses DESC LIMIT :limit")
    suspend fun soonestDue(limit: Int, statuses: List<WordStatus> = REVIEW_STATUSES): List<WordEntity>

    @Query("SELECT COUNT(*) FROM words WHERE status IN (:statuses) AND due <= :now")
    suspend fun countDue(now: Long, statuses: List<WordStatus> = REVIEW_STATUSES): Int

    @Query("SELECT * FROM words WHERE status IN (:statuses) ORDER BY updatedAt DESC")
    fun observeByStatus(statuses: List<WordStatus>): Flow<List<WordEntity>>

    @Query("DELETE FROM words WHERE lemma = :lemma")
    suspend fun delete(lemma: String)

    @Query("SELECT COUNT(*) FROM words")
    suspend fun count(): Int

    @Query("SELECT lemma, status FROM words")
    suspend fun statuses(): List<LemmaStatus>

    companion object {
        val REVIEW_STATUSES = listOf(WordStatus.LEARNING, WordStatus.FAMILIAR, WordStatus.MASTERED)
    }
}

@Dao
interface EncounterDao {
    @Insert
    suspend fun insert(e: EncounterEntity): Long

    @Query("SELECT * FROM encounters WHERE lemma = :lemma ORDER BY createdAt DESC")
    suspend fun forLemma(lemma: String): List<EncounterEntity>

    @Query("SELECT COUNT(*) FROM encounters WHERE lemma = :lemma AND sentence = :sentence")
    suspend fun countSame(lemma: String, sentence: String): Int

    @Query("UPDATE encounters SET aiMeaning = :meaning WHERE lemma = :lemma AND sentence = :sentence")
    suspend fun setAiMeaning(lemma: String, sentence: String, meaning: String)

    /** Keeps only the [keep] newest sentences per word. */
    @Query("DELETE FROM encounters WHERE lemma = :lemma AND id NOT IN (SELECT id FROM encounters WHERE lemma = :lemma ORDER BY createdAt DESC LIMIT :keep)")
    suspend fun trim(lemma: String, keep: Int)

    @Query("DELETE FROM encounters WHERE lemma = :lemma")
    suspend fun deleteFor(lemma: String)
}

@Dao
interface ReviewLogDao {
    @Insert
    suspend fun insert(log: ReviewLogEntity)

    /** Every log entry since [since], oldest first (reviews and word-list additions). */
    @Query("SELECT at, source FROM review_log WHERE at >= :since ORDER BY at")
    fun observeSince(since: Long): Flow<List<LogPoint>>

    @Query("SELECT COUNT(*) FROM review_log WHERE source != 'ADD'")
    fun observeTotalReviews(): Flow<Int>
}

data class LogPoint(val at: Long, val source: ReviewSource)

data class LemmaStatus(val lemma: String, val status: WordStatus)

@Dao
interface DailyReadingDao {
    @Query("INSERT OR IGNORE INTO daily_reading (day, words, seconds) VALUES (:day, 0, 0)")
    suspend fun ensure(day: Long)

    @Query("UPDATE daily_reading SET words = words + :words, seconds = seconds + :seconds WHERE day = :day")
    suspend fun add(day: Long, words: Int, seconds: Long)

    @Query("SELECT * FROM daily_reading WHERE day >= :fromDay ORDER BY day")
    fun observeFrom(fromDay: Long): Flow<List<DailyReadingEntity>>

    @Query("SELECT COALESCE(SUM(words), 0) FROM daily_reading")
    fun observeTotalWords(): Flow<Int>
}
