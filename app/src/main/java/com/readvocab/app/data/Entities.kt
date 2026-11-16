package com.readvocab.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.readvocab.core.srs.MemoryState
import com.readvocab.core.vocab.WordStatus

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String,
    val chapterCount: Int,
    val wordCount: Int,
    val addedAt: Long,
    val lastReadAt: Long = 0,
    val chapterIndex: Int = 0,
    val paragraphIndex: Int = 0,
)

@Entity(tableName = "chapters", primaryKeys = ["bookId", "idx"])
data class ChapterEntity(
    val bookId: Long,
    val idx: Int,
    val title: String,
    val wordCount: Int,
)

/** The user's record for one lemma. Words without a record fall back to the vocab-test boundary. */
@Entity(tableName = "words", indices = [Index("status"), Index("due")])
data class WordEntity(
    @PrimaryKey val lemma: String,
    val status: WordStatus,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val due: Long = 0,
    val lastReview: Long = 0,
    val reps: Int = 0,
    val lapses: Int = 0,
    val lookups: Int = 0,
    /** Times the word was read past without being tapped while it was highlighted as new. */
    val passes: Int = 0,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val memory: MemoryState get() = MemoryState(stability, difficulty, due, lastReview, reps, lapses)

    fun withMemory(m: MemoryState, now: Long, status: WordStatus = WordStatus.fromMemory(m)) = copy(
        status = status, stability = m.stability, difficulty = m.difficulty, due = m.due,
        lastReview = m.lastReview, reps = m.reps, lapses = m.lapses, updatedAt = now,
    )
}

/** A sentence in which the user met a word; used for cloze review cards. */
@Entity(tableName = "encounters", indices = [Index("lemma")])
data class EncounterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lemma: String,
    val surface: String,
    val sentence: String,
    val bookId: Long?,
    val bookTitle: String,
    val createdAt: Long,
    /** Cached AI contextual meaning (short Chinese gloss), if fetched. */
    val aiMeaning: String? = null,
)

enum class ReviewSource { LOOKUP, READING, CARD }

@Entity(tableName = "review_log", indices = [Index("at")])
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lemma: String,
    val rating: Int,
    val source: ReviewSource,
    val at: Long,
)
