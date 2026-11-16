package com.readvocab.app

import android.app.Application
import com.readvocab.app.data.AppDatabase
import com.readvocab.app.data.BookRepository
import com.readvocab.app.data.DictionaryRepository
import com.readvocab.app.data.SettingsStore
import com.readvocab.app.data.WordRepository
import com.readvocab.core.ai.AiProviders
import com.readvocab.core.ai.AiTutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Manual dependency container; small enough that a DI framework is not worth it yet. */
class AppContainer(app: Application) {
    val database = AppDatabase.create(app)
    val dictionary = DictionaryRepository(app)
    val books = BookRepository(app, database)
    val words = WordRepository(database)
    val settings = SettingsStore(app)

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
    }
}
