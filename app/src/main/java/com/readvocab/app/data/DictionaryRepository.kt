package com.readvocab.app.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.readvocab.core.text.Lemmatizer
import com.readvocab.core.text.PhraseEntry
import com.readvocab.core.vocab.WordFamily
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
    private var familyRanks: HashMap<String, Int> = HashMap()
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready

    val lemmatizer = Lemmatizer({ lemmas[it] }, { ranks.containsKey(it) }, { ranks[it] })

    suspend fun ensureLoaded() = mutex.withLock {
        if (_ready.value) return@withLock
        withContext(Dispatchers.IO) {
            val file = context.getDatabasePath(DB_NAME)
            val prefs = context.getSharedPreferences("dict", Context.MODE_PRIVATE)
            if (!file.exists() || prefs.getInt("version", 0) != DB_VERSION) {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "$DB_NAME.tmp")
                // Shipped gzipped (as .gzip: the Android build unpacks .gz assets) because the phrase table makes it big.
                java.util.zip.GZIPInputStream(context.assets.open("$DB_NAME.gzip")).use { input -> tmp.outputStream().use { input.copyTo(it) } }
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
            // Spelling variants and transparent derivations take the rank of their family's commonest member.
            val eff = HashMap<String, Int>(r.size)
            for ((w, rank) in r) {
                val e = WordFamily.effectiveRank(w) { r[it] } ?: rank
                if (e != rank) eff[w] = e
            }
            db = database; ranks = r; lemmas = l; familyRanks = eff
        }
        _ready.value = true
    }

    /**
     * Frequency rank of a headword (0 = headword without frequency data), or null if not a headword.
     * A British spelling or a transparent derivation gets its family's rank (see [WordFamily]).
     */
    fun rank(word: String): Int? = familyRanks[word] ?: ranks[word]

    fun lemma(surface: String): String = lemmatizer.lemma(surface)

    /** A word's entry; for a phrase on the word list (it contains a space), the phrase's entry. */
    suspend fun lookup(word: String): DictEntry? = withContext(Dispatchers.IO) {
        ensureLoaded()
        if (' ' in word) return@withContext lookupPhrase(word)
        db!!.rawQuery("SELECT word, phonetic, translation, tag, rank, exchange FROM dict WHERE word = ?", arrayOf(word)).use { c ->
            if (!c.moveToFirst()) null
            else DictEntry(
                word = c.getString(0), phonetic = c.getString(1), translation = c.getString(2),
                tags = c.getString(3).split(' ').filter { it.isNotBlank() }, rank = c.getInt(4), exchange = c.getString(5),
            )
        }
    }

    private val phraseCache = java.util.concurrent.ConcurrentHashMap<String, List<PhraseEntry>>()

    /** Phrases whose first word has lemma [first] (catch → catch up, catch up with, …). Call off the main thread. */
    fun phrasesStartingWith(first: String): List<PhraseEntry> = phraseCache.getOrPut(first) {
        val d = db ?: return@getOrPut emptyList()
        d.rawQuery("SELECT phrase, key, kind FROM phrase WHERE key >= ? AND key < ?", arrayOf("$first ", "$first!")).use { c ->
            buildList { while (c.moveToNext()) add(PhraseEntry(c.getString(0), c.getString(1).split(' '), c.getInt(2))) }
        }
    }

    /** A phrase's dictionary entry, shaped like a word's (no phonetic, no rank). */
    suspend fun lookupPhrase(phrase: String): DictEntry? = withContext(Dispatchers.IO) {
        ensureLoaded()
        db!!.rawQuery("SELECT phrase, translation FROM phrase WHERE phrase = ?", arrayOf(phrase)).use { c ->
            if (!c.moveToFirst()) null
            else DictEntry(word = c.getString(0), phonetic = "", translation = c.getString(1), tags = emptyList(), rank = 0, exchange = "")
        }
    }

    /** Random well-known headwords within a frequency band, for the vocabulary test. */
    suspend fun sample(band: Band, n: Int): List<String> = withContext(Dispatchers.IO) {
        ensureLoaded()
        db!!.rawQuery(
            "SELECT word FROM dict WHERE rank BETWEEN ? AND ? AND length(word) >= 3 AND (collins > 0 OR oxford > 0 OR tag != '') " +
                "AND word NOT LIKE '%-%' AND word NOT LIKE '%''%' ORDER BY RANDOM() LIMIT ?",
            arrayOf(band.from.toString(), band.to.toString(), (n * 3).toString()),
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
            // "defence" is ranked as rare but tests the reader on "defense": leave such words out.
            .filter { (rank(it) ?: 0) >= band.from }
            .take(n)
    }

    companion object {
        private const val DB_NAME = "ecdict.db"
        private const val DB_VERSION = 3
    }
}
