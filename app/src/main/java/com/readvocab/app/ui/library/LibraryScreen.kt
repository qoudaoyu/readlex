package com.readvocab.app.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.readvocab.app.AppContainer
import com.readvocab.app.data.BookEntity
import com.readvocab.app.ui.appViewModel
import com.readvocab.core.vocab.WordStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class LibraryState(
    val books: List<BookEntity> = emptyList(),
    val vocabSize: Int = 0,
    val dueCount: Int = 0,
    val learningCount: Int = 0,
    val importing: Boolean = false,
)

class LibraryViewModel(private val c: AppContainer) : ViewModel() {
    private val importing = MutableStateFlow(false)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    val state = combine(c.books.observeBooks(), c.words.observeAll(), c.settings.settings, importing) { books, words, settings, imp ->
        val now = System.currentTimeMillis()
        LibraryState(
            books = books,
            vocabSize = settings.vocabSize,
            dueCount = words.count { it.status.isInReview && it.due <= now },
            learningCount = words.count { it.status == WordStatus.LEARNING || it.status == WordStatus.FAMILIAR },
            importing = imp,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryState())

    private fun runImport(block: suspend () -> Long, onOpen: (Long) -> Unit) = viewModelScope.launch {
        importing.value = true
        try {
            val id = block()
            onOpen(id)
        } catch (e: Exception) {
            messages.emit("导入失败：${e.message}")
        } finally {
            importing.value = false
        }
    }

    fun importUri(uri: Uri, onOpen: (Long) -> Unit) = runImport({ c.books.importUri(uri) }, onOpen)
    fun importText(title: String, text: String, onOpen: (Long) -> Unit) = runImport({ c.books.importText(title, text) }, onOpen)
    fun delete(id: Long) = viewModelScope.launch { c.books.delete(id) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(onOpenBook: (Long) -> Unit, onStartTest: () -> Unit, onReview: () -> Unit) {
    val vm = appViewModel { LibraryViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showPaste by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<BookEntity?>(null) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importUri(uri, onOpenBook)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("阅读背单词") },
                actions = {
                    IconButton(onClick = { showPaste = true }) { Icon(Icons.Outlined.ContentPaste, contentDescription = "粘贴文章") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { picker.launch(arrayOf("text/plain", "application/epub+zip", "application/octet-stream")) },
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text("导入 TXT / EPUB") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SummaryCard(state, onStartTest, onReview) }
            if (state.importing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.books.isEmpty()) item {
                Text("还没有书。点右下角导入一本你想读的英文书，或者用右上角粘贴一篇文章。", style = MaterialTheme.typography.bodyMedium)
            }
            items(state.books, key = { it.id }) { book ->
                Card(
                    modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { onOpenBook(book.id) }, onLongClick = { deleting = book }),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                        val progress = if (book.chapterCount == 0) 0f else (book.chapterIndex + 1f) / book.chapterCount
                        LinearProgressIndicator(progress = { if (book.lastReadAt == 0L) 0f else progress }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${book.chapterCount} 章 · 约 ${book.wordCount} 词" + if (book.lastReadAt > 0) " · 读到第 ${book.chapterIndex + 1} 章" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    if (showPaste) PasteDialog(onDismiss = { showPaste = false }) { title, text ->
        showPaste = false
        vm.importText(title, text, onOpenBook)
    }
    deleting?.let { book ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除《${book.title}》？") },
            text = { Text("生词和复习记录会保留。") },
            confirmButton = { TextButton(onClick = { vm.delete(book.id); deleting = null }) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SummaryCard(state: LibraryState, onStartTest: () -> Unit, onReview: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (state.vocabSize > 0) {
                    Text("词汇量约 ${state.vocabSize}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                } else {
                    Text("先测一下词汇量", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("5 分钟，用来决定阅读时标出哪些词", style = MaterialTheme.typography.bodySmall)
                }
                Text("学习中 ${state.learningCount} 词 · 待复习 ${state.dueCount} 词", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.width(8.dp))
            if (state.vocabSize == 0) TextButton(onClick = onStartTest) { Text("开始测试") }
            else if (state.dueCount > 0) TextButton(onClick = onReview) { Text("去复习") }
        }
    }
}

@Composable
private fun PasteDialog(onDismiss: () -> Unit, onImport: (String, String) -> Unit) {
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴一篇英文文章") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("标题（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(text, { text = it }, label = { Text("正文") }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp))
            }
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onImport(title, text) }) { Text("开始阅读") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
