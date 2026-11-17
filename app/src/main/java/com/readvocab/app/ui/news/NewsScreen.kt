package com.readvocab.app.ui.news

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.readvocab.app.AppContainer
import com.readvocab.app.data.FeedEntity
import com.readvocab.app.data.FeedItemEntity
import com.readvocab.app.ui.appViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class NewsState(
    val feeds: List<FeedEntity> = emptyList(),
    val items: List<FeedItemEntity> = emptyList(),
    /** Article link → book id, for headlines already imported. */
    val imported: Map<String, Long> = emptyMap(),
    val refreshing: Boolean = false,
    val filter: String? = null,
)

class NewsViewModel(private val c: AppContainer) : ViewModel() {
    private val refreshing = MutableStateFlow(false)
    private val filter = MutableStateFlow<String?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 2)

    val state = combine(c.news.observeFeeds(), c.news.observeItems(), c.books.observeBooks(), refreshing, filter) { feeds, items, books, r, f ->
        NewsState(
            feeds = feeds,
            items = if (f == null) items else items.filter { it.feedUrl == f },
            imported = books.filter { it.sourceUrl.isNotBlank() }.associate { it.sourceUrl to it.id },
            refreshing = r,
            filter = f,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NewsState())

    init {
        viewModelScope.launch {
            c.news.ensureDefaults()
            // Refresh on open, unless it was done in the last half hour.
            val last = c.settings.current.lastNewsRefresh
            if (System.currentTimeMillis() - last > 30 * 60 * 1000L) refresh(quiet = true)
        }
    }

    fun setFilter(url: String?) { filter.value = url }

    fun refresh(quiet: Boolean = false) {
        if (refreshing.value) return
        refreshing.value = true
        viewModelScope.launch {
            try {
                val r = c.news.refresh()
                c.settings.update { it.copy(lastNewsRefresh = System.currentTimeMillis()) }
                if (r.failed.isNotEmpty()) messages.emit("${r.failed.joinToString("、")} 暂时刷新不了，显示的是上次的内容")
                else if (!quiet) messages.emit("已更新")
            } finally {
                refreshing.value = false
            }
        }
    }

    fun setEnabled(url: String, enabled: Boolean) = viewModelScope.launch { c.news.setEnabled(url, enabled) }
    fun remove(url: String) = viewModelScope.launch {
        c.news.removeFeed(url)
        if (filter.value == url) filter.value = null
    }

    val adding = MutableStateFlow(false)
    fun addFeed(url: String, onDone: () -> Unit) {
        adding.value = true
        viewModelScope.launch {
            try {
                val f = c.news.addFeed(url)
                messages.emit("已添加：${f.title}")
                onDone()
            } catch (e: Exception) {
                messages.emit(e.message ?: "添加失败")
            } finally {
                adding.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewsScreen(onOpenUrl: (String?) -> Unit, onOpenBook: (Long) -> Unit) {
    val vm = appViewModel { NewsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var managing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    val titles = state.feeds.associate { it.url to it.title }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("新闻") },
                actions = {
                    IconButton(onClick = { vm.refresh() }, enabled = !state.refreshing) { Icon(Icons.Outlined.Refresh, "刷新") }
                    IconButton(onClick = { onOpenUrl(null) }) { Icon(Icons.Outlined.Language, "浏览器") }
                    IconButton(onClick = { managing = true }) { Icon(Icons.Outlined.Tune, "管理订阅源") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            val enabled = state.feeds.filter { it.enabled }
            if (enabled.size > 1) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = state.filter == null, onClick = { vm.setFilter(null) }, label = { Text("全部") }) }
                    items(enabled, key = { it.url }) { f ->
                        FilterChip(selected = state.filter == f.url, onClick = { vm.setFilter(f.url) }, label = { Text(f.title) })
                    }
                }
            }
            LazyColumn(
                Modifier.fillMaxSize().testTag("news-list"),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (state.items.isEmpty() && !state.refreshing) item {
                    Text(
                        "还没有新闻。点右上角刷新；如果一直是空的，检查一下网络，或者在\"管理订阅源\"里换几个源。也可以点地球图标，直接在内置浏览器里打开网站。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                items(state.items, key = { it.link }) { item ->
                    val bookId = state.imported[item.link]
                    Headline(item, titles[item.feedUrl].orEmpty(), bookId != null) {
                        if (bookId != null) onOpenBook(bookId) else onOpenUrl(item.link)
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (managing) ManageFeeds(vm, state.feeds, onDismiss = { managing = false })
}

@Composable
private fun Headline(item: FeedItemEntity, source: String, imported: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOf(source, relativeTime(item.publishedAt)).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (imported) Text("已导入", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (item.summary.isNotBlank()) {
            Text(item.summary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ManageFeeds(vm: NewsViewModel, feeds: List<FeedEntity>, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    val adding by vm.adding.collectAsStateWithLifecycle()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("订阅源") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                feeds.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.title, style = MaterialTheme.typography.bodyLarge)
                            f.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2) }
                        }
                        Switch(checked = f.enabled, onCheckedChange = { vm.setEnabled(f.url, it) })
                        IconButton(onClick = { vm.remove(f.url) }) { Icon(Icons.Outlined.Delete, "删除") }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text("添加 RSS 订阅源", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(url, { url = it }, placeholder = { Text("https://…/rss.xml") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row {
                    TextButton(enabled = url.isNotBlank() && !adding, onClick = { vm.addFeed(url) { url = "" } }) {
                        Text(if (adding) "正在检查…" else "添加")
                    }
                }
                Text("很多新闻网站在页面底部或\"RSS\"页面列出订阅地址。", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
    )
}

private fun relativeTime(at: Long, now: Long = System.currentTimeMillis()): String {
    if (at <= 0) return ""
    val minutes = (now - at) / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 24 * 60 -> "${minutes / 60} 小时前"
        minutes < 7 * 24 * 60 -> "${minutes / (24 * 60)} 天前"
        else -> java.text.SimpleDateFormat("M月d日", java.util.Locale.CHINA).format(java.util.Date(at))
    }
}
