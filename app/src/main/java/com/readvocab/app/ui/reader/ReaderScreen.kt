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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.readvocab.core.text.ParaMarkup
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
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.readvocab.app.data.BookEntity
import com.readvocab.core.vocab.Coverage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

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
    val coverage by vm.coverage.collectAsStateWithLifecycle()
    var showStyle by remember { mutableStateOf(false) }
    val layouts = remember { HashMap<Int, TextLayoutResult>() }
    val density = LocalDensity.current
    val horizontalPadPx = with(density) { READER_H_PADDING.toPx() }
    val quotePadPx = with(density) { QUOTE_TOP.toPx() }
    val headingPadPx = with(density) { HEADING_PAD.toPx() }
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

    val systemDark = isSystemInDarkTheme()
    val colors = when (readerTheme(settings.readerTheme)) {
        ReaderTheme.SYSTEM -> if (systemDark) ReaderDark else ReaderLight
        ReaderTheme.LIGHT -> ReaderLight
        ReaderTheme.DARK -> ReaderDark
    }
    val dark = colors.dark
    val book = state.book
    // Articles and AI stories get the magazine header: kicker, headline, standfirst, meta bar.
    val magazine = book != null && (book.isArticle || book.isStory) && state.chapters.size <= 1
    val ledeIndex = if (magazine) state.paragraphs.indexOfFirst { it.kind == ParaMarkup.Kind.BODY } else -1
    val highlightFor = remember(statuses, settings.knownBoundary, settings.highlightMode) {
        { lemma: String -> vm.highlight(lemma, statuses, settings) }
    }
    // A one-chapter text (an article, a pasted text) has no chapter name worth showing.
    val chapterTitle = if (state.chapters.size > 1) state.chapters.getOrNull(state.chapterIndex)?.title.orEmpty() else ""

    Scaffold(
        containerColor = colors.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colors.background,
                    titleContentColor = colors.text,
                    navigationIconContentColor = colors.text,
                    actionIconContentColor = colors.text,
                ),
                title = {
                    Column {
                        val barTitle = if (magazine) siteOf(book!!.author).ifBlank { book.title } else book?.title.orEmpty()
                        Text(barTitle, style = MaterialTheme.typography.titleMedium.copy(fontFamily = ReaderSans, fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (chapterTitle.isNotEmpty()) Text(chapterTitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                    IconButton(onClick = { showStyle = true }) {
                        Text("Aa", fontFamily = ReaderSerif, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.semantics { contentDescription = "字号和夜间模式" })
                    }
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
            val kind = state.paragraphs[paraIndex].kind
            val dy = when (kind) {
                ParaMarkup.Kind.QUOTE -> quotePadPx
                ParaMarkup.Kind.HEADING -> headingPadPx
                else -> 0f
            }
            val inText = y - item.offset - dy
            // Below the text (the standfirst and meta bar under a headline) is not a word.
            if (inText < 0 || inText > layout.size.height) return null
            return paraIndex to layout.getOffsetForPosition(Offset(pos.x - horizontalPadPx, inText))
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
            verticalArrangement = Arrangement.spacedBy((settings.fontSizeSp * 0.9f).dp),
        ) {
            // Item 0: the chapter name, or for an article the kicker (its source) above the headline.
            item {
                when {
                    chapterTitle.isNotEmpty() -> Text(
                        chapterTitle,
                        fontFamily = ReaderSerif, fontWeight = FontWeight.Bold, fontSize = (settings.fontSizeSp * 1.35f).sp,
                        lineHeight = (settings.fontSizeSp * 1.7f).sp, color = colors.heading,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    magazine && siteOf(book!!.author).isNotBlank() -> Text(
                        siteOf(book.author).uppercase(),
                        fontFamily = ReaderSans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, letterSpacing = 1.4.sp,
                        color = colors.accent, modifier = Modifier.padding(top = 12.dp),
                    )
                    else -> Spacer(Modifier.height(0.dp))
                }
            }
            itemsIndexed(state.paragraphs) { i, para ->
                // Selected sentences in one paragraph are contiguous: shade them as one block, gaps included.
                val selected = selection?.let { sel ->
                    val idx = para.sentences.indices.filter { sel.contains(SentencePos(i, it)) }
                    if (idx.isEmpty()) emptyList() else listOf(para.sentences[idx.first()].first..para.sentences[idx.last()].last)
                } ?: emptyList()
                Column {
                    ParagraphText(
                        para = para,
                        fontSizeSp = settings.fontSizeSp,
                        colors = colors,
                        lede = i == ledeIndex,
                        highlightFor = highlightFor,
                        selected = selected,
                        onLayout = { layouts[i] = it },
                        onTap = { vm.onTap(i, it) },
                    )
                    if (magazine && i == 0 && para.kind == ParaMarkup.Kind.TITLE) ArticleHeader(book!!, coverage, colors)
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (state.chapters.size <= 1) return@Row
                    OutlinedButton(enabled = state.chapterIndex > 0, onClick = { vm.goToChapter(state.chapterIndex - 1) }) { Text("上一章") }
                    Text("${state.chapterIndex + 1} / ${state.chapters.size}", Modifier.align(Alignment.CenterVertically), color = colors.muted)
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
    if (showStyle) {
        ReaderStyleSheet(
            fontSizeSp = settings.fontSizeSp,
            theme = readerTheme(settings.readerTheme),
            onFontSize = { v -> app.container.settings.update { it.copy(fontSizeSp = v) } },
            onTheme = { t -> app.container.settings.update { it.copy(readerTheme = t.name) } },
            onDismiss = { showStyle = false },
        )
    }

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
/** Space above a quotation for its rule; above a subheading for breathing room. */
private val QUOTE_TOP = 18.dp
private val HEADING_PAD = 12.dp

/** "BBC News · Jane Doe" → "BBC News". */
private fun siteOf(author: String) = author.substringBefore(" · ").trim()

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
    colors: ReaderColors,
    lede: Boolean,
    highlightFor: (String) -> Highlight,
    selected: List<IntRange>,
    onLayout: (TextLayoutResult) -> Unit,
    onTap: (Int) -> Unit,
) {
    val dark = colors.dark
    val selectionColor = colors.accent.copy(alpha = 0.18f)
    // Uppercase a subheading only when that keeps every offset where the tokens expect it.
    val shown = if (para.kind == ParaMarkup.Kind.HEADING) para.text.uppercase().takeIf { it.length == para.text.length } ?: para.text else para.text
    val annotated = remember(para, shown, highlightFor, colors, selected, lede, fontSizeSp) {
        buildAnnotatedString {
            append(shown)
            // The opening paragraph of an article starts with a raised initial in the accent colour.
            if (lede && shown.isNotEmpty() && shown[0].isLetter()) {
                addStyle(SpanStyle(fontSize = (fontSizeSp * 2.4f).sp, fontWeight = FontWeight.Bold, color = colors.accent), 0, 1)
            }
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
    val base = TextStyle(fontFamily = ReaderSerif, fontSize = fontSizeSp.sp, lineHeight = (fontSizeSp * 1.65f).sp, color = colors.text)
    val style = when (para.kind) {
        ParaMarkup.Kind.TITLE -> base.copy(fontWeight = FontWeight.Bold, fontSize = (fontSizeSp * 1.7f).sp, lineHeight = (fontSizeSp * 2.05f).sp, letterSpacing = (-0.2).sp, color = colors.heading)
        ParaMarkup.Kind.HEADING -> base.copy(fontFamily = ReaderSans, fontWeight = FontWeight.SemiBold, fontSize = (fontSizeSp * 0.75f).sp, lineHeight = (fontSizeSp * 1.2f).sp, letterSpacing = (fontSizeSp * 0.075f).sp, color = colors.accent)
        ParaMarkup.Kind.QUOTE -> base.copy(fontStyle = FontStyle.Italic, fontSize = (fontSizeSp * 1.2f).sp, lineHeight = (fontSizeSp * 1.65f).sp, color = colors.heading)
        ParaMarkup.Kind.BODY -> base
    }
    val decoration = when (para.kind) {
        ParaMarkup.Kind.QUOTE -> Modifier
            .drawBehind {
                drawRect(colors.strongRule, size = Size(size.width, 2.dp.toPx()))
                drawRect(colors.rule, topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx()))
            }
            .padding(top = QUOTE_TOP, bottom = 14.dp)
        ParaMarkup.Kind.HEADING -> Modifier.padding(top = HEADING_PAD)
        else -> Modifier
    }
    Text(
        text = annotated,
        style = style,
        onTextLayout = { layout = it; onLayout(it) },
        modifier = Modifier.fillMaxWidth()
            .then(decoration)
            .pointerInput(para) {
                detectTapGestures(onTap = { pos -> layout?.let { currentOnTap(it.getOffsetForPosition(pos)) } })
            },
    )
}

/** Under an article's headline: the standfirst in italics, then a ruled bar with byline, date, length and coverage. */
@Composable
private fun ArticleHeader(book: BookEntity, coverage: Double?, colors: ReaderColors) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        if (book.excerpt.isNotBlank()) {
            Text(
                book.excerpt,
                fontFamily = ReaderSerif, fontStyle = FontStyle.Italic, fontSize = 18.sp, lineHeight = 27.sp,
                color = colors.muted, modifier = Modifier.padding(bottom = 16.dp),
            )
        }
        HorizontalDivider(color = colors.rule, thickness = 1.dp)
        val meta = buildList {
            book.author.substringAfter(" · ", "").trim().takeIf { it.isNotEmpty() }?.let(::add)
            if (book.publishedAt > 0) add(formatDate(book.publishedAt))
            add("约 %,d 词 · %d 分钟".format(book.wordCount, readingMinutes(book.wordCount)))
        }.joinToString(" · ")
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(meta, fontFamily = ReaderSans, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, lineHeight = 18.sp, color = colors.muted, modifier = Modifier.weight(1f))
            if (coverage != null) {
                val level = Coverage.level(coverage)
                val (bg, fg) = coverageChipColors(level, colors.dark)
                Text(
                    "认识 ${(coverage * 100).toInt()}% · ${level.label}",
                    fontFamily = ReaderSans, fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp, color = fg,
                    modifier = Modifier.padding(start = 10.dp).background(bg, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
        }
        HorizontalDivider(color = colors.rule, thickness = 1.dp)
        Spacer(Modifier.height(8.dp))
    }
}

private fun formatDate(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
        .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderStyleSheet(
    fontSizeSp: Int,
    theme: ReaderTheme,
    onFontSize: (Int) -> Unit,
    onTheme: (ReaderTheme) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp)) {
            Text("字号 $fontSizeSp", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A", fontFamily = ReaderSerif, fontSize = 14.sp)
                Slider(
                    value = fontSizeSp.toFloat(),
                    onValueChange = { onFontSize(it.roundToInt()) },
                    valueRange = 15f..24f, steps = 8,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp).testTag("font-size"),
                )
                Text("A", fontFamily = ReaderSerif, fontSize = 24.sp)
            }
            Spacer(Modifier.height(16.dp))
            Text("配色", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                ReaderTheme.entries.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = t == theme,
                        onClick = { onTheme(t) },
                        shape = SegmentedButtonDefaults.itemShape(i, ReaderTheme.entries.size),
                    ) { Text(t.label) }
                }
            }
        }
    }
}
