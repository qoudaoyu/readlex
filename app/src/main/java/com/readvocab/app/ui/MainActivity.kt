package com.readvocab.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.readvocab.app.ui.library.LibraryScreen
import com.readvocab.app.ui.reader.ReaderScreen
import com.readvocab.app.ui.review.ReviewScreen
import com.readvocab.app.ui.settings.SettingsScreen
import com.readvocab.app.ui.theme.ReadVocabTheme
import com.readvocab.app.ui.vocabtest.VocabTestScreen
import com.readvocab.app.ui.words.WordbookScreen

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("library", "书架", Icons.AutoMirrored.Outlined.MenuBook),
    Tab("review", "复习", Icons.Outlined.Style),
    Tab("words", "生词本", Icons.Outlined.Bookmarks),
    Tab("settings", "设置", Icons.Outlined.Settings),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ReadVocabTheme { AppNav() } }
    }
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = TABS.any { it.route == route }

    fun goTab(r: String) = nav.navigate(r) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
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
                )
            }
            composable("review") { ReviewScreen() }
            composable("words") { WordbookScreen() }
            composable("settings") { SettingsScreen(onStartTest = { nav.navigate("vocabtest") }) }
            composable("vocabtest") { VocabTestScreen(onDone = { nav.popBackStack() }) }
            composable("reader/{bookId}", arguments = listOf(navArgument("bookId") { type = NavType.LongType })) {
                ReaderScreen(bookId = it.arguments!!.getLong("bookId"), onBack = { nav.popBackStack() })
            }
        }
    }
}
