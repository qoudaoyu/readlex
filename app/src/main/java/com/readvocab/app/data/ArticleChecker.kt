package com.readvocab.app.data

import android.content.Context
import android.net.ConnectivityManager
import com.readvocab.core.importer.ImportException
import com.readvocab.core.vocab.Candidate
import com.readvocab.core.vocab.Coverage
import com.readvocab.core.vocab.Recommender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** What is known about a news item's difficulty. */
/** [unknownWords]: expected number of different words the reader does not know yet. */
data class ItemDifficulty(val status: String, val words: Int, val coverage: Double?, val unknownWords: Int = 0) {
    /** Too little text to judge, usually a paywall: the reader may need to sign in. */
    val short: Boolean get() = status == ArticleCheckEntity.SHORT
}

data class CheckProgress(val done: Int, val total: Int)

/**
 * Reads recent news articles in the background, counts their words and keeps the counts, so the
 * news list can show each article's coverage and recommend the ones at the reader's level.
 * Coverage itself is recomputed from the counts whenever the word list or the estimate changes.
 */
class ArticleChecker(
    private val db: AppDatabase,
    private val reader: PageReader,
    private val articles: ArticleImporter,
    private val dictionary: DictionaryRepository,
    private val words: WordRepository,
    private val coverage: CoverageRepository,
    private val settings: SettingsStore,
) {
    private val dao = db.checks()
    private val mutex = Mutex()
    private val _progress = MutableStateFlow<CheckProgress?>(null)
    val progress: StateFlow<CheckProgress?> = _progress

    fun observe(): Flow<Map<String, ItemDifficulty>> =
        combine(dao.observeAll(), words.observeAll(), settings.settings) { checks, wordList, _ ->
            dictionary.ensureLoaded()
            val statuses = wordList.associate { it.lemma to it.status }
            checks.associate { c ->
                if (c.status != ArticleCheckEntity.OK) return@associate c.link to ItemDifficulty(c.status, c.words, null)
                val counts = decode(c.lemmas)
                c.link to ItemDifficulty(c.status, c.words, coverage.ofCounts(counts, statuses), coverage.expectedUnknown(counts, statuses))
            }
        }.flowOn(Dispatchers.Default)

    /** The items to suggest from [items], best first. */
    fun recommend(items: List<FeedItemEntity>, difficulty: Map<String, ItemDifficulty>, sourceOf: (FeedItemEntity) -> String, exclude: Set<String>, now: Long): List<FeedItemEntity> {
        val byLink = items.associateBy { it.link }
        val candidates = items.mapNotNull { item ->
            if (item.link in exclude) return@mapNotNull null
            val d = difficulty[item.link] ?: return@mapNotNull null
            val cov = d.coverage ?: return@mapNotNull null
            Candidate(item.link, sourceOf(item), cov, d.words, item.publishedAt)
        }
        return Recommender.pick(candidates, now).mapNotNull { byLink[it.id] }
    }

    /** Whether checking now would use mobile data (or a metered hotspot). */
    fun metered(context: Context): Boolean =
        runCatching { (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered }.getOrDefault(true)

    /** Checks recent items not checked yet. Returns how many were checked; does nothing if a run is already going. */
    suspend fun checkPending(now: Long = System.currentTimeMillis(), limit: Int = MAX_PER_RUN): Int {
        if (!mutex.tryLock()) return 0
        try {
            dictionary.ensureLoaded()
            val items = dao.pending(
                since = now - Recommender.MAX_AGE_MS,
                retryBefore = now - 6 * HOUR,
                shortRetryBefore = now - 24 * HOUR,
                limit = limit,
            )
            if (items.isEmpty()) return 0
            _progress.value = CheckProgress(0, items.size)
            val gate = Semaphore(2)
            var done = 0
            coroutineScope {
                items.map { item ->
                    async {
                        gate.withPermit {
                            dao.upsert(check(item.link, now))
                            synchronized(this@ArticleChecker) { done++ }
                            _progress.value = CheckProgress(done, items.size)
                        }
                    }
                }.awaitAll()
            }
            dao.pruneOrphans()
            return items.size
        } finally {
            _progress.value = null
            mutex.unlock()
        }
    }

    private suspend fun check(link: String, now: Long): ArticleCheckEntity {
        val raw = try {
            reader.read(link)
        } catch (e: Exception) {
            return ArticleCheckEntity(link, ArticleCheckEntity.FAILED, 0, "", now)
        }
        val article = try {
            articles.parseResult(raw)
        } catch (e: ImportException) {
            // No article, or only a teaser: too little to judge.
            return ArticleCheckEntity(link, ArticleCheckEntity.SHORT, 0, "", now)
        } catch (e: Exception) {
            return ArticleCheckEntity(link, ArticleCheckEntity.FAILED, 0, "", now)
        }
        val counts = withContext(Dispatchers.Default) {
            Coverage.lemmaCounts(article.text) { dictionary.lemma(it) }.filterKeys { dictionary.rank(it) != null }
        }
        return ArticleCheckEntity(link, ArticleCheckEntity.OK, article.wordCount, encode(counts), now)
    }

    companion object {
        const val MAX_PER_RUN = 30
        private const val HOUR = 3600_000L

        internal fun encode(counts: Map<String, Int>) = counts.entries.joinToString(",") { "${it.key}:${it.value}" }

        internal fun decode(s: String): Map<String, Int> = if (s.isEmpty()) emptyMap() else s.split(',').mapNotNull { e ->
            val i = e.lastIndexOf(':')
            if (i <= 0) null else e.substring(0, i) to (e.substring(i + 1).toIntOrNull() ?: return@mapNotNull null)
        }.toMap()
    }
}
