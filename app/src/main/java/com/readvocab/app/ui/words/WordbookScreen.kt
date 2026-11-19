package com.readvocab.app.ui.words

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.readvocab.app.AppContainer
import com.readvocab.app.data.DictEntry
import com.readvocab.app.data.EncounterEntity
import com.readvocab.app.data.WordEntity
import com.readvocab.app.ui.appViewModel
import com.readvocab.app.ui.formatDue
import com.readvocab.app.ui.reader.SentenceWithWord
import com.readvocab.core.vocab.WordStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class WordRow(val word: WordEntity, val brief: String)
data class WordDetail(val word: WordEntity, val entry: DictEntry?, val encounters: List<EncounterEntity>)

private val TABS = listOf(
    "学习中" to listOf(WordStatus.LEARNING),
    "熟悉" to listOf(WordStatus.FAMILIAR),
    "掌握" to listOf(WordStatus.MASTERED),
    "已知" to listOf(WordStatus.KNOWN),
    "忽略" to listOf(WordStatus.IGNORED),
)

@OptIn(ExperimentalCoroutinesApi::class)
class WordbookViewModel(private val c: AppContainer) : ViewModel() {
    val tab = MutableStateFlow(0)
    val detail = MutableStateFlow<WordDetail?>(null)

    val rows = tab.flatMapLatest { t ->
        c.words.observeByStatus(TABS[t].second).map { list ->
            c.dictionary.ensureLoaded()
            list.map { WordRow(it, c.dictionary.lookup(it.lemma)?.brief ?: c.words.encounters(it.lemma).firstNotNullOfOrNull { e -> e.aiMeaning }.orEmpty()) }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun open(w: WordEntity) = viewModelScope.launch {
        detail.value = WordDetail(w, c.dictionary.lookup(w.lemma), c.words.encounters(w.lemma))
    }

    fun setStatus(lemma: String, status: WordStatus) = viewModelScope.launch { c.words.setStatus(lemma, status); detail.value = null }
    fun delete(lemma: String) = viewModelScope.launch { c.words.delete(lemma); detail.value = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordbookScreen() {
    val vm = appViewModel { WordbookViewModel(it) }
    val tab by vm.tab.collectAsStateWithLifecycle()
    val rows by vm.rows.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()

    Scaffold(topBar = { TopAppBar(title = { Text("生词本") }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
                TABS.forEachIndexed { i, (label, _) -> Tab(selected = tab == i, onClick = { vm.tab.value = i }, text = { Text(label) }) }
            }
            if (rows.isEmpty()) {
                Text("这里还没有词。阅读时点一下不认识的词，它就会进入「学习中」。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn {
                items(rows, key = { it.word.lemma }) { row ->
                    ListItem(
                        headlineContent = { Text(row.word.lemma) },
                        supportingContent = { Text(row.brief, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { if (row.word.status.isInReview) Text(formatDue(row.word.due), style = MaterialTheme.typography.bodySmall) },
                        modifier = Modifier.clickable { vm.open(row.word) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    detail?.let { d ->
        AlertDialog(
            onDismissRequest = { vm.detail.value = null },
            title = { Text(d.word.lemma + (d.entry?.phonetic?.takeIf { it.isNotBlank() }?.let { "  /$it/" } ?: "")) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.entry?.let { Text(it.translation, style = MaterialTheme.typography.bodyMedium) }
                    Text("状态：${d.word.status.label} · 查过 ${d.word.lookups} 次 · 复习 ${d.word.reps} 次", style = MaterialTheme.typography.bodySmall)
                    if (d.encounters.isNotEmpty()) {
                        HorizontalDivider()
                        Text("遇到过的句子", style = MaterialTheme.typography.labelLarge)
                        d.encounters.forEach { e ->
                            SentenceWithWord(e.sentence, e.surface)
                            e.aiMeaning?.let { Text("本句中：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                        }
                    }
                }
            },
            confirmButton = {
                if (d.word.status == WordStatus.KNOWN || d.word.status == WordStatus.IGNORED) {
                    TextButton(onClick = { vm.setStatus(d.word.lemma, WordStatus.LEARNING) }) { Text("重新学习") }
                } else {
                    TextButton(onClick = { vm.setStatus(d.word.lemma, WordStatus.KNOWN) }) { Text("标为已知") }
                }
            },
            dismissButton = { TextButton(onClick = { vm.delete(d.word.lemma) }) { Text("删除") } },
        )
    }
}
