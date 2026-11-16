package com.readvocab.app.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.readvocab.app.AppContainer
import com.readvocab.app.data.DayStat
import com.readvocab.app.data.Stats
import com.readvocab.app.ui.appViewModel
import com.readvocab.app.ui.formatCount
import com.readvocab.app.ui.formatMinutes
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate

class StatsViewModel(c: AppContainer) : ViewModel() {
    val stats = c.stats.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(onBack: () -> Unit) {
    val vm = appViewModel { StatsViewModel(it) }
    val stats by vm.stats.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("学习统计") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        val s = stats ?: return@Scaffold
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()).testTag("stats"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Totals(s)
            Section("未来 7 天要复习的词", "今天这一栏包含已经到期的词。复习得越稳，后面的柱子越矮。") {
                BarChart(
                    values = s.forecast,
                    labels = s.forecast.indices.map { forecastLabel(it) },
                    detail = { i -> "${forecastLabel(i)}：${s.forecast[i]} 个词" },
                    labelEvery = true,
                    initial = 0,
                )
            }
            Section("近 30 天每天复习的次数", "包括卡片复习和阅读中顺带复习的词。点柱子看当天详情。") {
                BarChart(
                    values = s.history.map { it.reviews },
                    labels = s.history.map { dayLabel(it.day) },
                    detail = { i -> dayDetail(s.history[i]) },
                )
            }
            Section("近 30 天每天阅读的词数", "从你在阅读器里读过的段落统计。点柱子看当天详情。") {
                BarChart(
                    values = s.history.map { it.wordsRead },
                    labels = s.history.map { dayLabel(it.day) },
                    detail = { i -> dayDetail(s.history[i]) },
                )
            }
            Section("生词表的记忆状态", "按记忆稳定度划分：学习中不到 4 天，熟悉 4–21 天，已掌握 21 天以上。") {
                MemoryBar(s)
            }
        }
    }
}

@Composable
private fun Totals(s: Stats) {
    Card {
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
            Total("连续天数", formatCount(s.streak), Modifier.weight(1f))
            Total("生词表", formatCount(s.inList), Modifier.weight(1f))
            Total("累计复习", formatCount(s.totalReviews), Modifier.weight(1f))
            Total("累计阅读词数", formatCount(s.totalWordsRead), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Total(label: String, value: String, modifier: Modifier, unit: String? = null) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value + (unit ?: ""), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Section(title: String, note: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        content()
    }
}

/**
 * Single-series bar chart: one hue, thin bars with rounded tops anchored to the baseline,
 * a recessive baseline, and a tap-to-inspect readout instead of a number on every bar.
 */
@Composable
private fun BarChart(
    values: List<Int>,
    labels: List<String>,
    detail: (Int) -> String,
    labelEvery: Boolean = false,
    initial: Int = values.lastIndex,
) {
    if (values.all { it == 0 }) {
        Text("还没有数据，用几天就有了。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var selected by remember(values) { mutableStateOf(initial) }
    val max = values.max().coerceAtLeast(1)
    val bar = MaterialTheme.colorScheme.primary
    val dim = bar.copy(alpha = 0.45f)
    val axis = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
        Text(detail(selected), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.height(24.dp))
        Row {
            Text(formatCount(max), style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.width(36.dp))
            Canvas(
                Modifier.weight(1f).height(120.dp).pointerInput(values) {
                    detectTapGestures { pos ->
                        val slot = size.width / values.size.toFloat()
                        selected = (pos.x / slot).toInt().coerceIn(0, values.lastIndex)
                    }
                },
            ) {
                val slot = size.width / values.size
                val w = (slot * 0.6f).coerceAtMost(28.dp.toPx())
                values.forEachIndexed { i, v ->
                    if (v == 0) return@forEachIndexed
                    val h = (size.height - 2.dp.toPx()) * v / max
                    val left = i * slot + (slot - w) / 2
                    roundedTopBar(left, size.height - h, w, h, if (i == selected) bar else dim)
                }
                drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
            }
        }
        if (labelEvery) {
            Row(Modifier.padding(start = 36.dp).fillMaxWidth()) {
                labels.forEach { l ->
                    Text(l, style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f))
                }
            }
        } else {
            Row(Modifier.padding(start = 36.dp).fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(labels.first(), style = MaterialTheme.typography.labelSmall, color = muted)
                Text(labels.last(), style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
    }
}

private fun DrawScope.roundedTopBar(left: Float, top: Float, width: Float, height: Float, color: Color) {
    val r = minOf(4.dp.toPx(), width / 2, height)
    val path = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                left, top, left + width, top + height,
                topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r),
                bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero,
            ),
        )
    }
    drawPath(path, color)
}

/** A progression from weak to strong memory, so one hue from light to dark. */
@Composable
private fun MemoryBar(s: Stats) {
    val base = MaterialTheme.colorScheme.primary
    val parts = listOf(
        Triple("学习中", s.learning, base.copy(alpha = 0.3f)),
        Triple("熟悉", s.familiar, base.copy(alpha = 0.6f)),
        Triple("已掌握", s.mastered, base),
    )
    val total = parts.sumOf { it.second }
    if (total == 0) {
        Text("生词表还是空的。读书时点开生词，按\"加入生词表\"就会出现在这里。", style = MaterialTheme.typography.bodyMedium)
        return
    }
    Row(Modifier.fillMaxWidth().height(20.dp).clip(RoundedCornerShape(4.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        parts.filter { it.second > 0 }.forEach { (_, n, color) ->
            Box(Modifier.weight(n.toFloat()).height(20.dp).background(color))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 4.dp)) {
        parts.forEach { (label, n, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
                Spacer(Modifier.width(6.dp))
                Text("$label $n", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (s.known > 0) {
        Text("另有 ${s.known} 个词已标为认识，不再复习。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun forecastLabel(i: Int): String = when (i) {
    0 -> "今天"
    1 -> "明天"
    else -> weekday(LocalDate.now().plusDays(i.toLong()).dayOfWeek)
}

private fun weekday(d: DayOfWeek) = "周" + "一二三四五六日"[d.value - 1]

private fun dayLabel(d: LocalDate) = if (d == LocalDate.now()) "今天" else "${d.monthValue}/${d.dayOfMonth}"

private fun dayDetail(d: DayStat): String {
    val day = if (d.day == LocalDate.now()) "今天" else "${d.day.monthValue}月${d.day.dayOfMonth}日"
    return "$day：复习 ${d.reviews} · 新词 ${d.added} · 阅读 ${formatCount(d.wordsRead)} 词 · ${formatMinutes(d.seconds)}"
}
