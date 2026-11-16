package com.readvocab.app.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readvocab.app.AppContainer
import com.readvocab.app.data.AppSettings
import com.readvocab.app.data.BookEntity
import com.readvocab.app.data.ChapterEntity
import com.readvocab.app.data.DictEntry
import com.readvocab.app.data.PassedWord
import com.readvocab.app.data.WordEntity
import com.readvocab.app.ui.Async
import com.readvocab.core.ai.AiException
import com.readvocab.core.ai.ContextualMeaning
import com.readvocab.core.ai.SentenceExplanation
import com.readvocab.core.text.Tokenizer
import com.readvocab.core.vocab.Highlight
import com.readvocab.core.vocab.WordClassifier
import com.readvocab.core.vocab.WordStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class TokenInfo(val start: Int, val end: Int, val surface: String, val lemma: String)
data class Para(val text: String, val tokens: List<TokenInfo>)

data class ReaderState(
    val book: BookEntity? = null,
    val chapters: List<ChapterEntity> = emptyList(),
    val chapterIndex: Int = 0,
    val paragraphs: List<Para> = emptyList(),
    /** Paragraph to scroll to after a chapter loads. */
    val startParagraph: Int = 0,
    val loading: Boolean = true,
)

data class LookupState(
    val surface: String,
    val lemma: String,
    val sentence: String,
    val entry: DictEntry?,
    val word: WordEntity?,
    val ai: Async<ContextualMeaning> = Async.Idle,
)

data class SentenceState(val sentence: String, val context: String, val ai: Async<SentenceExplanation> = Async.Idle)

class ReaderViewModel(private val c: AppContainer, private val bookId: Long) : ViewModel() {
    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    val settings: StateFlow<AppSettings> = c.settings.settings

    val statuses: StateFlow<Map<String, WordStatus>> = c.words.observeAll()
        .map { list -> list.associate { it.lemma to it.status } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _lookup = MutableStateFlow<LookupState?>(null)
    val lookup: StateFlow<LookupState?> = _lookup.asStateFlow()

    private val _sentence = MutableStateFlow<SentenceState?>(null)
    val sentence: StateFlow<SentenceState?> = _sentence.asStateFlow()

    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Lemmas the user tapped since the last commit; they are not counted as "read without help". */
    private val tapped = HashSet<String>()
    private var seenUpTo = -1
    private var committedUpTo = -1
    private var aiJob: Job? = null

    init {
        viewModelScope.launch {
            c.dictionary.ensureLoaded()
            val book = c.books.get(bookId) ?: return@launch
            val chapters = c.books.chapters(bookId)
            _state.update { it.copy(book = book, chapters = chapters) }
            loadChapter(book.chapterIndex.coerceIn(0, (chapters.size - 1).coerceAtLeast(0)), book.paragraphIndex)
        }
    }

    fun highlight(lemma: String, statuses: Map<String, WordStatus>, s: AppSettings): Highlight =
        WordClassifier.highlight(c.dictionary.rank(lemma), statuses[lemma], s.knownBoundary, s.highlightMode)

    private suspend fun loadChapter(index: Int, startParagraph: Int = 0) {
        _state.update { it.copy(loading = true) }
        val text = c.books.chapterText(bookId, index)
        val paras = withContext(Dispatchers.Default) {
            Tokenizer.paragraphs(text).map { p ->
                Para(p, Tokenizer.tokenize(p).map { t -> TokenInfo(t.start, t.end, t.text, c.dictionary.lemma(t.text)) })
            }
        }
        seenUpTo = -1
        committedUpTo = -1
        tapped.clear()
        _state.update { it.copy(chapterIndex = index, paragraphs = paras, startParagraph = startParagraph.coerceIn(0, paras.size), loading = false) }
        c.books.saveProgress(bookId, index, startParagraph)
    }

    fun goToChapter(index: Int) {
        val s = _state.value
        if (index !in s.chapters.indices || index == s.chapterIndex) return
        viewModelScope.launch {
            commitNow()
            loadChapter(index)
        }
    }

    /** Called as the user scrolls: paragraphs before [lastFullyRead] (inclusive) have been read. */
    fun onRead(lastFullyRead: Int, firstVisible: Int) {
        if (lastFullyRead > seenUpTo) seenUpTo = lastFullyRead
        // Commit in batches so reviews happen as you read, not only when leaving.
        if (seenUpTo - committedUpTo >= 6) viewModelScope.launch { commitNow() }
        progressParagraph = firstVisible
    }

    private var progressParagraph = 0

    fun onPause() {
        val s = _state.value
        appScope.launch {
            commitNow()
            c.books.saveProgress(bookId, s.chapterIndex, progressParagraph)
        }
    }

    private val commitMutex = Mutex()

    private suspend fun commitNow() = commitMutex.withLock { commitLocked() }

    private suspend fun commitLocked() {
        val s = _state.value
        val from = committedUpTo + 1
        val to = minOf(seenUpTo, s.paragraphs.size - 1)
        if (to < from) return
        committedUpTo = to
        val st = statuses.value
        val settings = c.settings.current
        val passed = LinkedHashMap<String, PassedWord>()
        for (i in from..to) {
            val p = s.paragraphs[i]
            for (t in p.tokens) {
                if (t.lemma in tapped || t.lemma in passed) continue
                if (c.dictionary.rank(t.lemma) == null) continue
                val h = highlight(t.lemma, st, settings)
                val status = st[t.lemma]
                // Only words that matter: highlighted ones or ones under review.
                if (h == Highlight.NONE && status?.isInReview != true) continue
                passed[t.lemma] = PassedWord(t.lemma, t.surface, Tokenizer.sentenceAt(p.text, t.start), h == Highlight.NEW)
            }
        }
        val result = c.words.commitReading(passed.values, bookId, s.book?.title.orEmpty(), settings.autoKnownAfter)
        if (result.reviewed > 0 || result.autoKnown > 0) {
            val parts = buildList {
                if (result.reviewed > 0) add("在阅读中复习了 ${result.reviewed} 个词")
                if (result.autoKnown > 0) add("${result.autoKnown} 个词多次读过未查，已标为认识")
            }
            messages.tryEmit(parts.joinToString("，"))
        }
    }

    private fun findToken(para: Para, offset: Int): TokenInfo? =
        para.tokens.firstOrNull { offset >= it.start && offset < it.end } ?: para.tokens.firstOrNull { offset == it.end }

    fun onTap(paraIndex: Int, offset: Int) {
        val para = _state.value.paragraphs.getOrNull(paraIndex) ?: return
        val token = findToken(para, offset) ?: return
        val sentence = Tokenizer.sentenceAt(para.text, token.start)
        tapped += token.lemma
        aiJob?.cancel()
        viewModelScope.launch {
            val entry = c.dictionary.lookup(token.lemma) ?: c.dictionary.lookup(token.surface.lowercase())
            val word = if (entry != null) {
                c.words.onLookup(entry.word, token.surface, sentence, bookId, _state.value.book?.title.orEmpty())
            } else c.words.get(token.lemma)
            _lookup.value = LookupState(token.surface, entry?.word ?: token.lemma, sentence, entry, word)
            if (c.settings.current.autoAi && c.tutor() != null) fetchMeaning()
        }
    }

    fun dismissLookup() { aiJob?.cancel(); _lookup.value = null }

    fun fetchMeaning() {
        val l = _lookup.value ?: return
        val tutor = c.tutor() ?: run { _lookup.value = l.copy(ai = Async.Failed("请先在「设置」里填写 AI 的 API Key")); return }
        _lookup.value = l.copy(ai = Async.Loading)
        aiJob?.cancel()
        aiJob = viewModelScope.launch {
            val result = try {
                val m = withContext(Dispatchers.IO) { tutor.contextualMeaning(l.surface, l.lemma, l.sentence, l.entry?.translation) }
                if (m.meaning.isNotBlank()) c.words.saveAiMeaning(l.lemma, l.sentence, m.meaning)
                Async.Done(m)
            } catch (e: AiException) {
                Async.Failed(e.message ?: "AI 请求失败")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Async.Failed("AI 请求失败：${e.message}")
            }
            _lookup.update { cur -> if (cur?.lemma == l.lemma && cur.sentence == l.sentence) cur.copy(ai = result) else cur }
        }
    }

    fun setStatus(status: WordStatus) {
        val l = _lookup.value ?: return
        viewModelScope.launch {
            c.words.setStatus(l.lemma, status)
            _lookup.value = l.copy(word = c.words.get(l.lemma))
        }
    }

    fun onLongPress(paraIndex: Int, offset: Int) {
        val para = _state.value.paragraphs.getOrNull(paraIndex) ?: return
        val sentence = Tokenizer.sentenceAt(para.text, offset)
        if (sentence.isBlank()) return
        _sentence.value = SentenceState(sentence, para.text)
        explainSentence()
    }

    fun explainSentence() {
        val s = _sentence.value ?: return
        val tutor = c.tutor() ?: run { _sentence.value = s.copy(ai = Async.Failed("请先在「设置」里填写 AI 的 API Key，即可使用整句翻译和结构解析")); return }
        _sentence.value = s.copy(ai = Async.Loading)
        viewModelScope.launch {
            val result = try {
                Async.Done(withContext(Dispatchers.IO) { tutor.explainSentence(s.sentence, s.context) })
            } catch (e: AiException) {
                Async.Failed(e.message ?: "AI 请求失败")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Async.Failed("AI 请求失败：${e.message}")
            }
            _sentence.update { cur -> if (cur?.sentence == s.sentence) cur.copy(ai = result) else cur }
        }
    }

    fun dismissSentence() { _sentence.value = null }

    override fun onCleared() {
        onPause()
        super.onCleared()
    }

    companion object {
        /** Outlives the ViewModel so the last stretch of reading is still committed after leaving. */
        private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
