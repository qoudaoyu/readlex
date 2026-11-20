package com.readvocab.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Newspaper
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.readvocab.app.reminder.Reminders
import com.readvocab.app.ui.browser.BrowserScreen
import com.readvocab.app.ui.library.LibraryScreen
import com.readvocab.app.ui.news.NewsScreen
import com.readvocab.app.ui.stats.StatsScreen
import com.readvocab.app.ui.reader.ReaderScreen
import com.readvocab.app.ui.review.ReviewScreen
import com.readvocab.app.ui.settings.SettingsScreen
import com.readvocab.app.ui.theme.ReadVocabTheme
import com.readvocab.app.ui.vocabtest.VocabTestScreen
import com.readvocab.app.ui.words.WordbookScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("library", "书架", Icons.AutoMirrored.Outlined.MenuBook),
    Tab("news", "新闻", Icons.Outlined.Newspaper),
    Tab("review", "复习", Icons.Outlined.Style),
    Tab("words", "生词本", Icons.Outlined.Bookmarks),
    Tab("settings", "设置", Icons.Outlined.Settings),
)

class MainActivity : ComponentActivity() {
    /** Where a notification tap or a share from another app wants to go; consumed by the nav host. */
    private val request = mutableStateOf<NavRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) request.value = NavRequest.from(intent)
        setContent { ReadVocabTheme { AppNav(request) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        request.value = NavRequest.from(intent)
    }
}

@Composable
private fun AppNav(request: MutableState<NavRequest?>) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = TABS.any { it.route == route }

    fun goTab(r: String) = nav.navigate(r) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.readvocab.app.ReadVocabApp
    LaunchedEffect(request.value) {
        val r = request.value ?: return@LaunchedEffect
        request.value = null
        when (r) {
            is NavRequest.Tab -> if (TABS.any { it.route == r.route }) goTab(r.route)
            is NavRequest.OpenUrl -> nav.navigate(browserRoute(r.url))
            is NavRequest.SharedText -> {
                val id = runCatching { app.container.books.importText(r.title, r.text) }.getOrNull()
                if (id != null) nav.navigate("reader/$id")
            }
        }
    }

    Scaffold(
        bottomBar = {
            if (showBar) NavigationBar {
                TABS.forEach { tab ->
                    NavigationBarItem(
                        selected = route == tab.route,
                        onClick = { goTab(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "library", modifier = if (showBar) Modifier.padding(bottom = padding.calculateBottomPadding()) else Modifier) {
            composable("library") {
                LibraryScreen(
                    onOpenBook = { nav.navigate("reader/$it") },
                    onStartTest = { nav.navigate("vocabtest") },
                    onReview = { goTab("review") },
                    onStats = { nav.navigate("stats") },
                    onOpenUrl = { nav.navigate(browserRoute(it)) },
                )
            }
            composable("news") {
                NewsScreen(onOpenUrl = { nav.navigate(browserRoute(it)) }, onOpenBook = { nav.navigate("reader/$it") })
            }
            composable(
                "browser?url={url}",
                arguments = listOf(navArgument("url") { type = NavType.StringType; nullable = true; defaultValue = null }),
            ) {
                BrowserScreen(
                    initialUrl = it.arguments?.getString("url"),
                    onBack = { nav.popBackStack() },
                    onOpenBook = { id -> nav.navigate("reader/$id") },
                )
            }
            composable("review") { ReviewScreen(onOpenBook = { nav.navigate("reader/$it") }) }
            composable("words") { WordbookScreen() }
            composable("settings") { SettingsScreen(onStartTest = { nav.navigate("vocabtest") }) }
            composable("stats") { StatsScreen(onBack = { nav.popBackStack() }) }
            composable("vocabtest") { VocabTestScreen(onDone = { nav.popBackStack() }) }
            composable("reader/{bookId}", arguments = listOf(navArgument("bookId") { type = NavType.LongType })) {
                ReaderScreen(bookId = it.arguments!!.getLong("bookId"), onBack = { nav.popBackStack() })
            }
        }
    }
}

private fun browserRoute(url: String?) = if (url.isNullOrBlank()) "browser" else "browser?url=" + android.net.Uri.encode(url)

/** Navigation asked for from outside the UI: a notification tap or a share from another app. */
sealed interface NavRequest {
    data class Tab(val route: String) : NavRequest
    data class OpenUrl(val url: String) : NavRequest
    data class SharedText(val title: String, val text: String) : NavRequest

    companion object {
        private val URL = Regex("https?://[^\\s<>\"]+")

        fun from(intent: Intent?): NavRequest? {
            intent ?: return null
            intent.getStringExtra(Reminders.EXTRA_TAB)?.let { return Tab(it) }
            if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty().trim()
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty().trim()
                return fromSharedText(text, subject)
            }
            return null
        }

        /**
         * A share is usually "Headline https://link" from a browser or news app: open the link so the
         * article can be extracted. A long passage without a link is imported as it is.
         */
        fun fromSharedText(text: String, subject: String = ""): NavRequest? {
            val url = URL.find(text)?.value?.trimEnd('.', ',', ')', ']')
            val withoutUrl = if (url != null) text.replace(url, "").trim() else text
            return when {
                url != null && withoutUrl.length < 400 -> OpenUrl(url)
                text.isNotBlank() -> SharedText(subject.ifBlank { withoutUrl.lineSequence().first().take(60) }, withoutUrl)
                else -> null
            }
        }
    }
}
