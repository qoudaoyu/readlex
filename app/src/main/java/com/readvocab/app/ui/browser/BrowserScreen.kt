package com.readvocab.app.ui.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.readvocab.app.AppContainer
import com.readvocab.app.data.NewsRepository
import com.readvocab.app.ui.Async
import com.readvocab.app.ui.appViewModel
import com.readvocab.app.ui.formatCount
import com.readvocab.core.vocab.Coverage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

data class Imported(val bookId: Long, val title: String, val words: Int, val coverage: Double?, val existed: Boolean)

class BrowserViewModel(private val c: AppContainer) : ViewModel() {
    val importing = MutableStateFlow<Async<Imported>>(Async.Idle)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 2)

    fun script() = c.articles.extractionScript()

    fun onExtracted(raw: String?) {
        viewModelScope.launch {
            importing.value = try {
                val article = c.articles.parseResult(raw)
                val existing = c.books.bySourceUrl(article.url)
                val id = c.articles.save(article)
                Async.Done(Imported(id, article.title, article.wordCount, c.coverage.ofText(article.text), existing != null))
            } catch (e: Exception) {
                messages.emit(e.message ?: "导入失败")
                Async.Idle
            }
        }
    }

    fun startImport() { importing.value = Async.Loading }
    fun dismiss() { importing.value = Async.Idle }
}

/** Turns what the user typed into a URL, or a web search when it does not look like one. */
fun normalizeAddress(input: String): String {
    val t = input.trim()
    if (t.isEmpty()) return ""
    if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(t)) return t
    if (!t.contains(' ') && Regex("^[\\w-]+(\\.[\\w-]+)+(/.*)?$").matches(t)) return "https://$t"
    return "https://www.bing.com/search?q=" + java.net.URLEncoder.encode(t, "UTF-8")
}

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(initialUrl: String?, onBack: () -> Unit, onOpenBook: (Long) -> Unit) {
    val vm = appViewModel { BrowserViewModel(it) }
    val importing by vm.importing.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var address by remember { mutableStateOf(initialUrl.orEmpty()) }
    var currentUrl by remember { mutableStateOf(initialUrl.orEmpty()) }
    var progress by remember { mutableIntStateOf(100) }
    var canGoBack by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val webView = remember {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            // Keep sign-ins (for subscriptions) across sessions.
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    // Only web pages; app links (intent://, market://) are ignored.
                    val scheme = request.url.scheme?.lowercase()
                    return scheme != "http" && scheme != "https"
                }

                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    currentUrl = url; address = url; canGoBack = view.canGoBack()
                }

                override fun onPageFinished(view: WebView, url: String) {
                    currentUrl = url; address = url; canGoBack = view.canGoBack()
                    CookieManager.getInstance().flush()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
            }
            if (!initialUrl.isNullOrBlank()) loadUrl(initialUrl)
        }
    }
    DisposableEffect(Unit) { onDispose { webView.stopLoading(); webView.destroy() } }

    fun go(url: String) {
        val u = normalizeAddress(url)
        if (u.isEmpty()) return
        currentUrl = u; address = u
        webView.loadUrl(u)
    }

    val onStartPage = currentUrl.isBlank()
    BackHandler {
        when {
            webView.canGoBack() -> webView.goBack()
            else -> onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Outlined.Close, "关闭") } },
                title = {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        placeholder = { Text("输入网址或搜索") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { go(address) }),
                        modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
                    )
                },
                actions = {
                    if (!onStartPage) {
                        if (canGoBack) IconButton(onClick = { webView.goBack() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "后退") }
                        IconButton(onClick = { webView.reload() }) { Icon(Icons.Outlined.Refresh, "刷新") }
                    }
                },
            )
        },
        floatingActionButton = {
            if (!onStartPage) {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (importing == Async.Loading) return@ExtendedFloatingActionButton
                        vm.startImport()
                        webView.evaluateJavascript(vm.script()) { vm.onExtracted(it) }
                    },
                    icon = { Icon(Icons.Outlined.Download, null) },
                    text = { Text(if (importing == Async.Loading) "正在提取正文…" else "导入阅读") },
                    modifier = Modifier.testTag("import-article"),
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView({ webView }, Modifier.fillMaxSize())
            if (onStartPage) StartPage(onOpen = ::go)
            if (!onStartPage && progress < 100) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
        }
    }

    (importing as? Async.Done)?.value?.let { r ->
        AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text(if (r.existed) "这篇已经在书架上了" else "已导入书架") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(r.title, style = MaterialTheme.typography.titleMedium)
                    Text("约 ${formatCount(r.words)} 词", style = MaterialTheme.typography.bodyMedium)
                    r.coverage?.let { cov ->
                        Text("你认识其中约 ${percent(cov)} 的词（${Coverage.level(cov).label}）", style = MaterialTheme.typography.bodyMedium)
                        Text(levelHint(cov), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.dismiss(); onOpenBook(r.bookId) }) { Text("开始阅读") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("继续浏览") } },
        )
    }
}

fun percent(cov: Double): String = "${(cov * 100).toInt()}%"

fun levelHint(cov: Double): String = when (Coverage.level(cov)) {
    Coverage.Level.EASY -> "生词很少，读起来轻松，适合练速度。"
    Coverage.Level.JUST_RIGHT -> "难度正合适：大部分能读懂，又有一些生词可学。"
    Coverage.Level.CHALLENGING -> "生词偏多，可以多借助查词和整句翻译。"
    Coverage.Level.HARD -> "生词很多，读起来会比较吃力，可以先挑更简单的文章。"
}

@Composable
private fun StartPage(onOpen: (String) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("browser-start"),
    ) {
        item(span = { GridItemSpan(2) }) {
            Text(
                "打开一篇英文文章，点右下角\"导入阅读\"，正文就会放进书架。需要订阅的网站，在这里登录一次就行。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(NewsRepository.BOOKMARKS) { site ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(site.url) }) {
                Column(Modifier.padding(16.dp)) {
                    Text(site.name, style = MaterialTheme.typography.titleSmall)
                    Text(site.url.removePrefix("https://").removePrefix("www.").substringBefore('/'), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
