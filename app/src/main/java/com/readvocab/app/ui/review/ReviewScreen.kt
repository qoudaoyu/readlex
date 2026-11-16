package com.readvocab.app.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.readvocab.app.AppContainer
import com.readvocab.app.data.DictEntry
import com.readvocab.app.data.EncounterEntity
import com.readvocab.app.data.WordEntity
import com.readvocab.app.ui.appViewModel
import com.readvocab.app.ui.reader.SentenceWithWord
import com.readvocab.core.srs.Rating
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ReviewCard(val word: WordEntity, val entry: DictEntry?, val encounter: EncounterEntity?)

data class ReviewState(
    val loading: Boolean = true,
    val queue: List<ReviewCard> = emptyList(),
    val index: Int = 0,
    val revealed: Boolean = false,
    val done: Int = 0,
) {
    val current: ReviewCard? get() = queue.getOrNull(index)
}

class ReviewViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(ReviewState())
    val state = _state.asStateFlow()

    fun reload() = viewModelScope.launch {
        _state.value = ReviewState(loading = true)
        c.dictionary.ensureLoaded()
        val cards = c.words.due(limit = 50).map { w ->
            ReviewCard(w, c.dictionary.lookup(w.lemma), c.words.encounters(w.lemma).firstOrNull())
        }
        _state.value = ReviewState(loading = false, queue = cards)
    }

    fun reveal() = _state.update { it.copy(revealed = true) }

    fun rate(rating: Rating) {
        val card = _state.value.current ?: return
        viewModelScope.launch {
            c.words.rate(card.word.lemma, rating)
            _state.update { it.copy(index = it.index + 1, revealed = false, done = it.done + 1) }
        }
    }
}

/** Replaces the word in the sentence with a blank that keeps the first letter as a hint. */
fun cloze(sentence: String, surface: String): String {
    val regex = Regex("\\b" + Regex.escape(surface) + "\\b", RegexOption.IGNORE_CASE)
    return regex.replace(sentence) { m -> m.value.first() + "_".repeat((m.value.length - 1).coerceIn(3, 10)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen() {
    val vm = appViewModel { ReviewViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.reload() }

    Scaffold(topBar = { TopAppBar(title = { Text("复习") }) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val card = state.current
            when {
                state.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                card == null -> {
                    Text(
                        if (state.done > 0) "完成了 ${state.done} 个词的复习。" else "现在没有需要复习的词。",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "大部分复习会在阅读中自动完成：学习中的词在书里再次出现、你读过去没有点它，就算一次成功复习。" +
                            "这里只出现那些到期了、但最近没在阅读里碰到的词。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = { vm.reload() }) { Text("刷新") }
                }
                else -> {
                    LinearProgressIndicator(progress = { state.index.toFloat() / state.queue.size }, modifier = Modifier.fillMaxWidth())
                    Text("${state.index + 1} / ${state.queue.size}", style = MaterialTheme.typography.bodySmall)
                    ReviewCardView(card, state.revealed)
                    if (!state.revealed) {
                        Button(onClick = vm::reveal, modifier = Modifier.fillMaxWidth()) { Text("显示答案") }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RateButton("忘了", Rating.Again, vm, Modifier.weight(1f))
                            RateButton("模糊", Rating.Hard, vm, Modifier.weight(1f))
                            RateButton("记得", Rating.Good, vm, Modifier.weight(1f))
                            RateButton("简单", Rating.Easy, vm, Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RateButton(label: String, rating: Rating, vm: ReviewViewModel, modifier: Modifier) {
    val colors = if (rating == Rating.Again) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
    else ButtonDefaults.buttonColors()
    Button(onClick = { vm.rate(rating) }, colors = colors, modifier = modifier) { Text(label) }
}

@Composable
private fun ReviewCardView(card: ReviewCard, revealed: Boolean) {
    val enc = card.encounter
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (enc != null) {
                Text("回想空格处的词和它在这句里的意思", style = MaterialTheme.typography.labelMedium)
                if (!revealed) Text(cloze(enc.sentence, enc.surface), style = MaterialTheme.typography.bodyLarge)
                else SentenceWithWord(enc.sentence, enc.surface, MaterialTheme.typography.bodyLarge)
                if (enc.bookTitle.isNotBlank()) Text("——《${enc.bookTitle}》", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("回想这个词的意思", style = MaterialTheme.typography.labelMedium)
            }
            if (revealed || enc == null) {
                Text(card.word.lemma, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Start)
            }
            if (revealed) {
                HorizontalDivider()
                card.entry?.phonetic?.takeIf { it.isNotBlank() }?.let { Text("/$it/", style = MaterialTheme.typography.bodyMedium) }
                enc?.aiMeaning?.let { Text("本句中：$it", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                card.entry?.let { Text(it.translation.lines().take(4).joinToString("\n"), style = MaterialTheme.typography.bodyMedium) }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}
