package com.readvocab.app.ui.reader

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.readvocab.app.ReadVocabApp
import com.readvocab.app.ui.appViewModel
import com.readvocab.app.ui.theme.HighlightColors
import com.readvocab.core.vocab.Highlight
import com.readvocab.core.vocab.HighlightMode
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(bookId: Long, onBack: () -> Unit) {
    val vm = appViewModel(key = "reader-$bookId") { ReaderViewModel(it, bookId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val statuses by vm.statuses.collectAsStateWithLifecycle()
    val lookup by vm.lookup.collectAsStateWithLifecycle()
    val sentence by vm.sentence.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val layouts = remember { HashMap<Int, TextLayoutResult>() }
    val density = LocalDensity.current
    val horizontalPadPx = with(density) { READER_H_PADDING.toPx() }
    val snackbar = remember { SnackbarHostState() }
    var showChapters by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val app = context.applicationContext as ReadVocabApp

    // Text-to-speech for pronunciation.
    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        var engine: TextToSpeech? = null
        engine = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) { engine?.language = Locale.US; tts.value = engine }
        }
        onDispose { engine?.shutdown() }
    }
    val speak: (String) -> Unit = { text -> tts.value?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rv") }

    // Commit reading progress when the app goes to background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) vm.onPause() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val listState = rememberLazyListState()
    // Scroll to the saved position whenever a chapter finishes loading.
    LaunchedEffect(state.chapterIndex, state.loading) {
        if (!state.loading) listState.scrollToItem(if (state.startParagraph > 0) state.startParagraph + 1 else 0)
    }
    // Item 0 is the chapter title; paragraph i is item i + 1. A paragraph counts as read once the next one is visible.
    LaunchedEffect(listState, state.paragraphs) {
        snapshotFlow {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            val last = visible.lastOrNull()
            val lastFullyRead = when {
                last == null -> -1
                last.index > state.paragraphs.size -> state.paragraphs.size - 1 // footer visible: chapter done
                last.offset + last.size <= info.viewportEndOffset -> last.index - 1
                else -> last.index - 2
            }
            lastFullyRead to ((visible.firstOrNull()?.index ?: 1) - 1).coerceAtLeast(0)
        }.collect { (read, first) -> vm.onRead(read, first) }
    }

    val dark = isSystemInDarkTheme()
    val highlightFor = remember(statuses, settings.knownBoundary, settings.highlightMode) {
        { lemma: String -> vm.highlight(lemma, statuses, settings) }
    }
    val chapterTitle = state.chapters.getOrNull(state.chapterIndex)?.title.orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.book?.title.orEmpty(), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(chapterTitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
                actions = {
                    IconButton(onClick = {
                        app.container.settings.update {
                            it.copy(highlightMode = if (it.highlightMode == HighlightMode.ALL) HighlightMode.LEARNING_ONLY else HighlightMode.ALL)
                        }
                    }) {
                        Icon(if (settings.highlightMode == HighlightMode.ALL) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, "切换生词高亮")
                    }
                    IconButton(onClick = {
                        app.container.settings.update { it.copy(fontSizeSp = if (it.fontSizeSp >= 24) 16 else it.fontSizeSp + 2) }
                    }) { Icon(Icons.Outlined.TextFields, "字号") }
                    IconButton(onClick = { showChapters = true }) { Icon(Icons.AutoMirrored.Outlined.List, "目录") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            selection?.let { sel ->
                BottomAppBar {
                    Text("已选 ${vm.selectionCount(sel)} 句", Modifier.padding(start = 16.dp).weight(1f))
                    TextButton(onClick = vm::clearSelection) { Text("取消") }
                    Button(onClick = vm::translateSelection, modifier = Modifier.padding(end = 12.dp)) {
                        Text("翻译并解析")
                    }
                }
            }
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        // Maps a pointer position inside the list to (paragraph index, character offset).
        fun hit(pos: Offset): Pair<Int, Int>? {
            val info = listState.layoutInfo
            val y = pos.y + info.viewportStartOffset
            val item = info.visibleItemsInfo.firstOrNull { y >= it.offset && y < it.offset + it.size } ?: return null
            val paraIndex = item.index - 1
            val layout = layouts[paraIndex] ?: return null
            if (paraIndex !in state.paragraphs.indices) return null
            return paraIndex to layout.getOffsetForPosition(Offset(pos.x - horizontalPadPx, y - item.offset))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("reader-list")
                .sentenceSelectionGesture(
                    onStart = { pos -> hit(pos)?.let { (p, o) -> vm.startSelection(p, o) } },
                    onMove = { pos -> hit(pos)?.let { (p, o) -> vm.extendSelection(p, o) } },
                ),
            contentPadding = PaddingValues(horizontal = READER_H_PADDING, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Text(chapterTitle, style = MaterialTheme.typography.headlineSmall) }
            itemsIndexed(state.paragraphs) { i, para ->
                // Selected sentences in one paragraph are contiguous: shade them as one block, gaps included.
                val selected = selection?.let { sel ->
                    val idx = para.sentences.indices.filter { sel.contains(SentencePos(i, it)) }
                    if (idx.isEmpty()) emptyList() else listOf(para.sentences[idx.first()].first..para.sentences[idx.last()].last)
                } ?: emptyList()
                ParagraphText(
                    para = para,
                    fontSizeSp = settings.fontSizeSp,
                    dark = dark,
                    highlightFor = highlightFor,
                    selected = selected,
                    onLayout = { layouts[i] = it },
                    onTap = { vm.onTap(i, it) },
                )
            }
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    OutlinedButton(enabled = state.chapterIndex > 0, onClick = { vm.goToChapter(state.chapterIndex - 1) }) { Text("上一章") }
                    Text("${state.chapterIndex + 1} / ${state.chapters.size}", Modifier.align(Alignment.CenterVertically))
                    OutlinedButton(enabled = state.chapterIndex < state.chapters.size - 1, onClick = { vm.goToChapter(state.chapterIndex + 1) }) { Text("下一章") }
                }
            }
        }
    }

    lookup?.let { l ->
        WordSheet(
            state = l,
            aiConfigured = settings.aiConfig.isUsable,
            onDismiss = vm::dismissLookup,
            onSpeak = speak,
            onAskAi = vm::fetchMeaning,
            onAdd = vm::addToWordList,
            onRemove = vm::removeFromWordList,
            onSetStatus = vm::setStatus,
        )
    }
    sentence?.let { s -> SentenceSheet(s, onDismiss = vm::dismissSentence, onRetry = vm::explainSentence, onSpeak = speak) }

    if (showChapters) {
        AlertDialog(
            onDismissRequest = { showChapters = false },
            title = { Text("目录") },
            text = {
                LazyColumn {
                    itemsIndexed(state.chapters) { i, ch ->
                        ListItem(
                            headlineContent = { Text(ch.title, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (i == state.chapterIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) },
                            supportingContent = { Text("${ch.wordCount} 词") },
                            modifier = Modifier.pointerInput(i) { detectTapGestures { showChapters = false; vm.goToChapter(i) } },
                        )
                        HorizontalDivider()
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showChapters = false }) { Text("关闭") } },
        )
    }
}

private val READER_H_PADDING = 20.dp

/**
 * Long-press then drag to select sentences. Watches the Initial pass so that, once the long press
 * fires, the drag is ours: the list does not scroll and the paragraph does not treat the release as a tap.
 */
private fun Modifier.sentenceSelectionGesture(onStart: (Offset) -> Unit, onMove: (Offset) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val cancelled = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull true
                    if (!c.pressed || c.isConsumed) return@withTimeoutOrNull true
                    if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) return@withTimeoutOrNull true
                }
                @Suppress("UNREACHABLE_CODE") true
            }
            if (cancelled != null) return@awaitEachGesture // a tap or a scroll: leave it to the list and the text
            onStart(down.position)
            while (true) {
                val ev = awaitPointerEvent(PointerEventPass.Initial)
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                c.consume()
                if (!c.pressed) break
                onMove(c.position)
            }
        }
    }

@Composable
private fun ParagraphText(
    para: Para,
    fontSizeSp: Int,
    dark: Boolean,
    highlightFor: (String) -> Highlight,
    selected: List<IntRange>,
    onLayout: (TextLayoutResult) -> Unit,
    onTap: (Int) -> Unit,
) {
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val annotated = remember(para, highlightFor, dark, selected, selectionColor) {
        buildAnnotatedString {
            append(para.text)
            for (r in selected) addStyle(SpanStyle(background = selectionColor), r.first, r.last + 1)
            for (t in para.tokens) {
                val style = when (highlightFor(t.lemma)) {
                    Highlight.NONE -> null
                    Highlight.NEW -> SpanStyle(color = HighlightColors.newWord(dark), textDecoration = TextDecoration.Underline)
                    Highlight.LEARNING -> SpanStyle(background = HighlightColors.learning(dark))
                    Highlight.FAMILIAR -> SpanStyle(background = HighlightColors.familiar(dark))
                }
                if (style != null) addStyle(style, t.start, t.end)
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val currentOnTap by rememberUpdatedState(onTap)
    Text(
        text = annotated,
        style = TextStyle(fontSize = fontSizeSp.sp, lineHeight = (fontSizeSp * 1.6f).sp, color = MaterialTheme.colorScheme.onSurface),
        onTextLayout = { layout = it; onLayout(it) },
        modifier = Modifier.fillMaxWidth().pointerInput(para) {
            detectTapGestures(onTap = { pos -> layout?.let { currentOnTap(it.getOffsetForPosition(pos)) } })
        },
    )
}
