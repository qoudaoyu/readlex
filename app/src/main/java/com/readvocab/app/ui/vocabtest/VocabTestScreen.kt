package com.readvocab.app.ui.vocabtest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.readvocab.app.AppContainer
import com.readvocab.app.ui.appViewModel
import com.readvocab.core.vocab.TestItem
import com.readvocab.core.vocab.VocabEstimator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VocabTestState(
    val loading: Boolean = true,
    val items: List<TestItem> = emptyList(),
    val page: Int = 0,
    val known: Set<String> = emptySet(),
    val result: Int? = null,
) {
    val pages: Int get() = (items.size + PAGE - 1) / PAGE
    val pageItems: List<TestItem> get() = items.drop(page * PAGE).take(PAGE)

    companion object { const val PAGE = 18 }
}

class VocabTestViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(VocabTestState())
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val items = VocabEstimator.BANDS.flatMapIndexed { i, band ->
                c.dictionary.sample(band, VocabEstimator.WORDS_PER_BAND).map { TestItem(it, i) }
            }
            // Shuffle within pages of nearby bands so difficulty still rises page by page.
            _state.value = VocabTestState(loading = false, items = items.chunked(VocabTestState.PAGE).flatMap { it.shuffled() })
        }
    }

    fun toggle(word: String) = _state.update { s -> s.copy(known = if (word in s.known) s.known - word else s.known + word) }

    fun next() {
        val s = _state.value
        if (s.page + 1 < s.pages) { _state.update { it.copy(page = it.page + 1) }; return }
        val size = VocabEstimator.estimate(s.items, s.known)
        val boundary = VocabEstimator.knownBoundary(s.items, s.known)
        c.settings.update { it.copy(vocabSize = size, knownBoundary = boundary) }
        _state.update { it.copy(result = size) }
    }

    fun back() = _state.update { it.copy(page = (it.page - 1).coerceAtLeast(0)) }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun VocabTestScreen(onDone: () -> Unit) {
    val vm = appViewModel { VocabTestViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("词汇量测试") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val result = state.result
            when {
                state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                result != null -> {
                    Text("你的词汇量约为", style = MaterialTheme.typography.titleMedium)
                    Text("$result 词", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "阅读时，比这个水平更常用的词默认当作已认识，不会被标出；更难的词会被轻轻标记。" +
                            "之后你点过的词会被记为「学习中」，读过多次都没点的生词会自动记为「已认识」，模型会随你的阅读不断修正。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onDone) { Text("开始阅读") }
                }
                else -> {
                    LinearProgressIndicator(progress = { (state.page + 1f) / state.pages }, modifier = Modifier.fillMaxWidth())
                    Text("第 ${state.page + 1} / ${state.pages} 页", style = MaterialTheme.typography.bodySmall)
                    Text("点选你能确定说出一个中文意思的词。不确定就不要选，估计会更准。", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        state.pageItems.forEach { item ->
                            FilterChip(
                                selected = item.word in state.known,
                                onClick = { vm.toggle(item.word) },
                                label = { Text(item.word, style = MaterialTheme.typography.titleMedium) },
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.page > 0) OutlinedButton(onClick = vm::back) { Text("上一页") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = vm::next) { Text(if (state.page + 1 < state.pages) "下一页" else "查看结果") }
                    }
                }
            }
        }
    }
}
