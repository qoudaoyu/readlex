package com.readvocab.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.readvocab.core.text.Lemmatizer
import com.readvocab.core.vocab.Band
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class DictEntry(
    val word: String,
    val phonetic: String,
    val translation: String,
    val tags: List<String>,
    val rank: Int,
    val exchange: String,
) {
    /** First line of the translation, for compact lists. */
    val brief: String get() = translation.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()

    val tagLabels: List<String> get() = tags.mapNotNull { TAG_NAMES[it] }

    companion object {
        private val TAG_NAMES = mapOf(
            "zk" to "中考", "gk" to "高考", "cet4" to "四级", "cet6" to "六级", "ky" to "考研",
            "toefl" to "托福", "ielts" to "雅思", "gre" to "GRE",
        )
    }
}

/** Read-only ECDICT database shipped in assets, plus in-memory rank and lemma tables. */
class DictionaryRepository(private val context: Context) {
    private val mutex = Mutex()
    private var db: SQLiteDatabase? = null
    private var ranks: HashMap<String, Int> = HashMap()
    private var lemmas: HashMap<String, String> = HashMap()
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    val lemmatizer = Lemmatizer({ lemmas[it] }, { ranks.containsKey(it) })

    suspend fun ensureLoaded() = mutex.withLock {
        if (_ready.value) return@withLock
        withContext(Dispatchers.IO) {
            val file = context.getDatabasePath(DB_NAME)
            val prefs = context.getSharedPreferences("dict", Context.MODE_PRIVATE)
            if (!file.exists() || prefs.getInt("version", 0) != DB_VERSION) {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "$DB_NAME.tmp")
                context.assets.open(DB_NAME).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(file)
                prefs.edit().putInt("version", DB_VERSION).apply()
            }
            val database = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            val r = HashMap<String, Int>(60_000)
            database.rawQuery("SELECT word, rank FROM dict", null).use { c ->
                while (c.moveToNext()) r[c.getString(0)] = c.getInt(1)
            }
            val l = HashMap<String, String>(50_000)
            database.rawQuery("SELECT form, lemma FROM lemma", null).use { c ->
                while (c.moveToNext()) l[c.getString(0)] = c.getString(1)
            }
            db = database; ranks = r; lemmas = l
        }
        _ready.value = true
    }

    /** Frequency rank of a headword (0 = headword without frequency data), or null if not a headword. */
    fun rank(word: String): Int? = ranks[word]

    fun lemma(surface: String): String = lemmatizer.lemma(surface)

    suspend fun lookup(word: String): DictEntry? = withContext(Dispatchers.IO) {
        ensureLoaded()
        db!!.rawQuery("SELECT word, phonetic, translation, tag, rank, exchange FROM dict WHERE word = ?", arrayOf(word)).use { c ->
            if (!c.moveToFirst()) null
            else DictEntry(
                word = c.getString(0), phonetic = c.getString(1), translation = c.getString(2),
                tags = c.getString(3).split(' ').filter { it.isNotBlank() }, rank = c.getInt(4), exchange = c.getString(5),
            )
        }
    }

    /** Random well-known headwords within a frequency band, for the vocabulary test. */
    suspend fun sample(band: Band, n: Int): List<String> = withContext(Dispatchers.IO) {
        ensureLoaded()
        db!!.rawQuery(
            "SELECT word FROM dict WHERE rank BETWEEN ? AND ? AND length(word) >= 3 AND (collins > 0 OR oxford > 0 OR tag != '') " +
                "AND word NOT LIKE '%-%' AND word NOT LIKE '%''%' ORDER BY RANDOM() LIMIT ?",
            arrayOf(band.from.toString(), band.to.toString(), n.toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
    }

    companion object {
        private const val DB_NAME = "ecdict.db"
        private const val DB_VERSION = 1
    }
}
