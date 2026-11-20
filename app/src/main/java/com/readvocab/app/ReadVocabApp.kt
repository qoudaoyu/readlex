package com.readvocab.app

import android.app.Application
import com.readvocab.app.data.AppDatabase
import com.readvocab.app.data.ArticleChecker
import com.readvocab.app.data.ArticleImporter
import com.readvocab.app.data.WebPageReader
import com.readvocab.app.data.BackupManager
import com.readvocab.app.data.CoverageRepository
import com.readvocab.app.data.NewsRepository
import com.readvocab.app.data.StatsRepository
import com.readvocab.app.reminder.Reminders
import com.readvocab.app.data.BookRepository
import com.readvocab.app.data.DictionaryRepository
import com.readvocab.app.data.SettingsStore
import com.readvocab.app.data.StoryGenerator
import com.readvocab.app.data.WordRepository
import com.readvocab.core.ai.AiProviders
import com.readvocab.core.ai.AiTutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Manual dependency container; small enough that a DI framework is not worth it yet. */
class AppContainer(val app: Application) {
    /** For work that should outlive a screen, such as checking article difficulty. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database = AppDatabase.create(app)
    val dictionary = DictionaryRepository(app)
    val books = BookRepository(app, database)
    val words = WordRepository(database)
    val settings = SettingsStore(app)
    val stories = StoryGenerator(words, books, dictionary, settings) { tutor() }
    val stats = StatsRepository(database)
    val backup = BackupManager(app, database)
    val coverage = CoverageRepository(books, dictionary, words, settings)
    val news = NewsRepository(database, settings = settings)
    val articles = ArticleImporter(app, books)
    val checker = ArticleChecker(database, WebPageReader(app) { articles.extractionScript() }, articles, dictionary, words, coverage, settings)

    /** Returns a tutor for the current AI settings, or null if no key is configured. */
    fun tutor(): AiTutor? {
        val config = settings.current.aiConfig
        return if (config.isUsable) AiTutor(AiProviders.create(config)) else null
    }
}

class ReadVocabApp : Application() {
    lateinit var container: AppContainer
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        scope.launch {
            container.dictionary.ensureLoaded()
            runCatching { container.books.importSampleIfEmpty() }
        }
        Reminders.ensureChannel(this)
        // Keeps the alarm in line with the settings, including right after install or update.
        scope.launch {
            container.settings.settings
                .map { it.reminderEnabled to it.reminderMinuteOfDay }
                .distinctUntilChanged()
                .collect { Reminders.sync(this@ReadVocabApp, container.settings.current) }
        }
    }
}
