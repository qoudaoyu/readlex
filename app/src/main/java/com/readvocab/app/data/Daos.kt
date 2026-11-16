package com.readvocab.app.data

import androidx.room.Dao
import androidx.room.Insert
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

    @Query("SELECT COUNT(*) FROM review_log WHERE at >= :since")
    fun observeCountSince(since: Long): Flow<Int>
}
