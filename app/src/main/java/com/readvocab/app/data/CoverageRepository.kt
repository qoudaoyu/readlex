package com.readvocab.app.data

import com.readvocab.core.vocab.Coverage
import com.readvocab.core.vocab.WordStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * How much of each book the reader already knows. Per-book lemma counts are computed once and
 * cached next to the book's text; the coverage itself is recomputed whenever the word list or
 * the vocabulary boundary changes, which only walks those counts.
 */
class CoverageRepository(
    private val books: BookRepository,
    private val dictionary: DictionaryRepository,
    private val words: WordRepository,
    private val settings: SettingsStore,
) {
    private val counts = ConcurrentHashMap<Long, Map<String, Int>>()
    private val mutex = Mutex()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(): Flow<Map<Long, Double>> =
        combine(books.observeBooks(), words.observeAll(), settings.settings) { b, w, s -> Triple(b, w, s) }
            .mapLatest { (bookList, wordList, s) ->
                dictionary.ensureLoaded()
                val statuses = wordList.associate { it.lemma to it.status }
                val curve = s.curve
                bookList.mapNotNull { book ->
                    val c = countsFor(book.id)
                    Coverage.expected(c) { Coverage.knownProbability(dictionary.rank(it), statuses[it], curve) }?.let { book.id to it }
                }.toMap()
            }
            .flowOn(Dispatchers.Default)

    /** Coverage of a text that is not in the library yet (an article about to be imported). */
    suspend fun ofText(text: String): Double? = withContext(Dispatchers.Default) {
        dictionary.ensureLoaded()
        val statuses = words.getAllStatuses()
        ofCounts(Coverage.lemmaCounts(text) { dictionary.lemma(it) }, statuses)
    }

    /** Expected number of different words in [counts] the reader does not know yet. */
    fun expectedUnknown(counts: Map<String, Int>, statuses: Map<String, WordStatus>): Int {
        val curve = settings.current.curve
        return counts.keys.sumOf { 1 - (Coverage.knownProbability(dictionary.rank(it), statuses[it], curve) ?: 1.0) }.toInt()
    }

    /** Coverage from lemma counts, with the current word list and settings. */
    fun ofCounts(counts: Map<String, Int>, statuses: Map<String, WordStatus>): Double? {
        val curve = settings.current.curve
        return Coverage.expected(counts) { Coverage.knownProbability(dictionary.rank(it), statuses[it], curve) }
    }

    private suspend fun countsFor(bookId: Long): Map<String, Int> {
        counts[bookId]?.let { return it }
        return mutex.withLock {
            counts[bookId] ?: load(bookId).also { counts[bookId] = it }
        }
    }

    private suspend fun load(bookId: Long): Map<String, Int> = withContext(Dispatchers.IO) {
        val file = books.cacheFile(bookId, CACHE)
        if (file.exists()) {
            runCatching {
                file.readLines().mapNotNull { line ->
                    val tab = line.indexOf('\t')
                    if (tab <= 0) null else line.substring(0, tab) to line.substring(tab + 1).toInt()
                }.toMap()
            }.getOrNull()?.let { return@withContext it }
        }
        val text = books.fullText(bookId)
        val c = withContext(Dispatchers.Default) { Coverage.lemmaCounts(text) { dictionary.lemma(it) } }
        // Only dictionary words matter for coverage; names and numbers are dropped from the cache.
        val kept = c.filterKeys { dictionary.rank(it) != null }
        if (file.parentFile?.exists() == true) {
            runCatching { file.writeText(kept.entries.joinToString("\n") { "${it.key}\t${it.value}" }) }
        }
        kept
    }

    fun forget(bookId: Long) { counts.remove(bookId) }

    companion object {
        const val CACHE = "lemmas-v3.tsv"
    }
}
