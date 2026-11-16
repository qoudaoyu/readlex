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
data class Para(val text: String, val tokens: List<TokenInfo>, val sentences: List<IntRange>) {
    fun sentenceIndexAt(offset: Int): Int =
        sentences.indexOfFirst { offset <= it.last }.let { if (it < 0) sentences.lastIndex else it }
}

data class SentencePos(val para: Int, val sentence: Int) : Comparable<SentencePos> {
    override fun compareTo(other: SentencePos) = compareValuesBy(this, other, { it.para }, { it.sentence })
}

/** A run of whole sentences, possibly spanning paragraphs. */
data class Selection(val anchor: SentencePos, val focus: SentencePos) {
    val start: SentencePos get() = minOf(anchor, focus)
    val end: SentencePos get() = maxOf(anchor, focus)
    fun contains(p: SentencePos) = p >= start && p <= end
}

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

data class SentenceState(val sentence: String, val context: String, val count: Int = 1, val ai: Async<SentenceExplanation> = Async.Idle)

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

    private val _selection = MutableStateFlow<Selection?>(null)
    val selection: StateFlow<Selection?> = _selection.asStateFlow()

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
                Para(
                    p,
                    Tokenizer.tokenize(p).map { t -> TokenInfo(t.start, t.end, t.text, c.dictionary.lemma(t.text)) },
                    Tokenizer.sentenceRanges(p),
                )
            }
        }
        seenUpTo = -1
        committedUpTo = -1
        tapped.clear()
        _selection.value = null
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
        if (_selection.value != null) { _selection.value = null; return }
        val para = _state.value.paragraphs.getOrNull(paraIndex) ?: return
        val token = findToken(para, offset) ?: return
        val sentence = Tokenizer.sentenceAt(para.text, token.start)
        tapped += token.lemma
        aiJob?.cancel()
        viewModelScope.launch {
            val entry = c.dictionary.lookup(token.lemma) ?: c.dictionary.lookup(token.surface.lowercase())
            val lemma = entry?.word ?: token.lemma
            val word = c.words.onLookup(lemma)
            _lookup.value = LookupState(token.surface, lemma, sentence, entry, word)
            if (c.settings.current.autoAi && c.tutor() != null) fetchMeaning()
        }
    }

    /** The prominent "加入生词表" button on the word card. */
    fun addToWordList() {
        val l = _lookup.value ?: return
        viewModelScope.launch {
            val meaning = (l.ai as? Async.Done)?.value?.meaning
            val w = c.words.addToLearning(l.lemma, l.surface, l.sentence, bookId, _state.value.book?.title.orEmpty(), meaning)
            _lookup.update { cur -> if (cur?.lemma == l.lemma) cur.copy(word = w) else cur }
            messages.tryEmit("已加入生词表：${l.lemma}")
        }
    }

    fun removeFromWordList() {
        val l = _lookup.value ?: return
        viewModelScope.launch {
            c.words.delete(l.lemma)
            _lookup.update { cur -> if (cur?.lemma == l.lemma) cur.copy(word = null) else cur }
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

    private fun posAt(paraIndex: Int, offset: Int): SentencePos? {
        val para = _state.value.paragraphs.getOrNull(paraIndex) ?: return null
        if (para.sentences.isEmpty()) return null
        return SentencePos(paraIndex, para.sentenceIndexAt(offset))
    }

    /** Long press: select the sentence under the finger. */
    fun startSelection(paraIndex: Int, offset: Int) {
        val p = posAt(paraIndex, offset) ?: return
        _selection.value = Selection(p, p)
    }

    /** Dragging after the long press: grow or shrink the selection a whole sentence at a time. */
    fun extendSelection(paraIndex: Int, offset: Int) {
        val sel = _selection.value ?: return
        val p = posAt(paraIndex, offset) ?: return
        if (p != sel.focus) _selection.value = sel.copy(focus = p)
    }

    fun clearSelection() { _selection.value = null }

    fun selectionCount(sel: Selection): Int {
        val paras = _state.value.paragraphs
        var n = 0
        for (pi in sel.start.para..sel.end.para) {
            val count = paras.getOrNull(pi)?.sentences?.size ?: 0
            val from = if (pi == sel.start.para) sel.start.sentence else 0
            val to = if (pi == sel.end.para) sel.end.sentence else count - 1
            n += (to - from + 1).coerceAtLeast(0)
        }
        return n
    }

    /** Translates and explains the selected sentences together. */
    fun translateSelection() {
        val sel = _selection.value ?: return
        val paras = _state.value.paragraphs
        val pieces = ArrayList<String>()
        for (pi in sel.start.para..sel.end.para) {
            val para = paras.getOrNull(pi) ?: continue
            val from = if (pi == sel.start.para) sel.start.sentence else 0
            val to = if (pi == sel.end.para) sel.end.sentence else para.sentences.lastIndex
            if (from > to) continue
            val text = para.text.substring(para.sentences[from].first, para.sentences[to].last + 1)
            pieces += text
        }
        if (pieces.isEmpty()) return
        val context = (sel.start.para..sel.end.para).mapNotNull { paras.getOrNull(it)?.text }.joinToString("\n")
        _selection.value = null
        _sentence.value = SentenceState(pieces.joinToString("\n"), context, selectionCount(sel))
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
