package com.readvocab.core.vocab

import com.readvocab.core.srs.MemoryState

/** Learning status of a word for this user. */
enum class WordStatus(val label: String) {
    /** Seen but never looked up; tracks passive passes. */
    NEW("生词"),
    LEARNING("学习中"),
    FAMILIAR("熟悉"),
    MASTERED("掌握"),
    /** The user said they already know it (or it was auto-promoted). */
    KNOWN("已知"),
    /** Names, junk tokens, words the user never wants highlighted. */
    IGNORED("忽略");

    val isInReview: Boolean get() = this == LEARNING || this == FAMILIAR || this == MASTERED

    companion object {
        fun fromMemory(state: MemoryState): WordStatus = when {
            state.stability >= 21.0 -> MASTERED
            state.stability >= 4.0 -> FAMILIAR
            else -> LEARNING
        }
    }
}

/** How a token is drawn in the reader. */
enum class Highlight { NONE, NEW, LEARNING, FAMILIAR }

enum class HighlightMode { ALL, LEARNING_ONLY }

object WordClassifier {
    /**
     * @param rank frequency rank of the lemma (1 = most frequent), or null if not a dictionary word
     * @param status the user's recorded status, or null when there is no record
     * @param knownBoundary words ranked at or below this are assumed known (from the vocab test)
     */
    fun highlight(rank: Int?, status: WordStatus?, knownBoundary: Int, mode: HighlightMode): Highlight {
        when (status) {
            WordStatus.LEARNING -> return Highlight.LEARNING
            WordStatus.FAMILIAR -> return if (mode == HighlightMode.ALL) Highlight.FAMILIAR else Highlight.LEARNING
            WordStatus.MASTERED, WordStatus.KNOWN, WordStatus.IGNORED -> return Highlight.NONE
            WordStatus.NEW, null -> Unit
        }
        // Not a headword (names, typos), or a headword with no frequency data (mostly
        // variant spellings and function-word forms): never flag as new.
        if (rank == null || rank <= 0) return Highlight.NONE
        if (rank <= knownBoundary) return Highlight.NONE
        return if (mode == HighlightMode.ALL) Highlight.NEW else Highlight.NONE
    }
}
