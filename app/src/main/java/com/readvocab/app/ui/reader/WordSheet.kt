package com.readvocab.app.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.readvocab.app.ui.Async
import com.readvocab.app.ui.formatDue
import com.readvocab.core.vocab.WordStatus

/** Renders [sentence] with every occurrence of [word] in bold. */
@Composable
fun SentenceWithWord(sentence: String, word: String, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium) {
    val text = buildAnnotatedString {
        val regex = Regex("\\b" + Regex.escape(word) + "\\b", RegexOption.IGNORE_CASE)
        var last = 0
        for (m in regex.findAll(sentence)) {
            append(sentence.substring(last, m.range.first))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) { append(m.value) }
            last = m.range.last + 1
        }
        append(sentence.substring(last))
    }
    Text(text, style = style)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WordSheet(
    state: LookupState,
    aiConfigured: Boolean,
    onDismiss: () -> Unit,
    onSpeak: (String) -> Unit,
    onAskAi: () -> Unit,
    onAdd: () -> Unit,
    onRemove: () -> Unit,
    onSetStatus: (WordStatus) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(state.lemma, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(8.dp))
                state.entry?.phonetic?.takeIf { it.isNotBlank() }?.let { Text("/$it/", style = MaterialTheme.typography.bodyMedium) }
                IconButton(onClick = { onSpeak(state.lemma) }) { Icon(Icons.AutoMirrored.Outlined.VolumeUp, "发音") }
            }
            if (!state.surface.equals(state.lemma, ignoreCase = true)) {
                Text("文中形式：${state.surface}", style = MaterialTheme.typography.bodySmall)
            }
            val word = state.word
            val inList = word?.status?.isInReview == true
            if (inList) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("✓ 已在生词表（${word!!.status.label}）", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onRemove) { Text("移出生词表") }
                }
            } else {
                Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.BookmarkAdd, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp))
                    Text("加入生词表")
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.entry?.tagLabels?.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                if (word != null && word.status.isInReview) {
                    AssistChip(onClick = {}, label = { Text("下次复习 ${formatDue(word.due)}") })
                }
            }
            if (state.entry != null) {
                Text(state.entry.translation.lines().take(6).joinToString("\n"), style = MaterialTheme.typography.bodyLarge)
            } else {
                Text("词典里没有这个词（可能是人名、地名或拼写变体）。", style = MaterialTheme.typography.bodyMedium)
            }

            HorizontalDivider()
            Text("原句", style = MaterialTheme.typography.labelLarge)
            SentenceWithWord(state.sentence, state.surface)

            Text("语境释义", style = MaterialTheme.typography.labelLarge)
            when (val ai = state.ai) {
                Async.Idle -> FilledTonalButton(onClick = onAskAi) {
                    Icon(Icons.Outlined.AutoAwesome, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp))
                    Text(if (aiConfigured) "AI 结合本句解释" else "AI 解释（需先在设置中填写 API Key）")
                }
                Async.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.size(8.dp)); Text("AI 正在分析语境…")
                }
                is Async.Failed -> Column {
                    Text(ai.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = onAskAi) { Text("重试") }
                }
                is Async.Done -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val m = ai.value
                    Text("${m.pos} ${m.meaning}".trim(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    if (m.explanation.isNotBlank()) Text(m.explanation, style = MaterialTheme.typography.bodyMedium)
                    if (m.sentenceTranslation.isNotBlank()) Text("译：${m.sentenceTranslation}", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                    m.collocations.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
                }
            }

            if (word?.status != WordStatus.KNOWN && word?.status != WordStatus.IGNORED && !inList) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onSetStatus(WordStatus.KNOWN); onDismiss() }) { Text("我认识，别再标出") }
                    TextButton(onClick = { onSetStatus(WordStatus.IGNORED); onDismiss() }) { Text("忽略") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentenceSheet(state: SentenceState, onDismiss: () -> Unit, onRetry: () -> Unit, onSpeak: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.count > 1) "翻译与解析（${state.count} 句）" else "整句解析", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = { onSpeak(state.sentence) }) { Icon(Icons.AutoMirrored.Outlined.VolumeUp, "朗读") }
            }
            Text(state.sentence, style = MaterialTheme.typography.bodyLarge)
            HorizontalDivider()
            when (val ai = state.ai) {
                Async.Idle, Async.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.size(8.dp)); Text("AI 正在解析…")
                }
                is Async.Failed -> Column {
                    Text(ai.message, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("重试") }
                }
                is Async.Done -> {
                    val e = ai.value
                    Text("翻译", style = MaterialTheme.typography.labelLarge)
                    Text(e.translation, style = MaterialTheme.typography.bodyLarge)
                    if (e.structure.isNotBlank()) {
                        Text("结构", style = MaterialTheme.typography.labelLarge)
                        Text(e.structure, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (e.notes.isNotEmpty()) {
                        Text("要点", style = MaterialTheme.typography.labelLarge)
                        e.notes.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
        }
    }
}
