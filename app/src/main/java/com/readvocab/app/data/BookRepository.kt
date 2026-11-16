package com.readvocab.app.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.readvocab.core.importer.BookImporter
import com.readvocab.core.importer.ImportException
import com.readvocab.core.importer.ParsedBook
import com.readvocab.core.importer.TxtImporter
import com.readvocab.core.text.Tokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/** Book metadata lives in Room; chapter text lives in files (chapters can exceed SQLite row limits). */
class BookRepository(private val context: Context, private val db: AppDatabase) {
    private val dao = db.books()

    fun observeBooks(): Flow<List<BookEntity>> = dao.observeAll()

    suspend fun get(id: Long) = dao.get(id)
    suspend fun chapters(bookId: Long) = dao.chapters(bookId)

    private fun bookDir(id: Long) = File(context.filesDir, "books/$id")

    suspend fun chapterText(bookId: Long, idx: Int): String = withContext(Dispatchers.IO) {
        File(bookDir(bookId), "$idx.txt").takeIf { it.exists() }?.readText().orEmpty()
    }

    suspend fun saveProgress(bookId: Long, chapter: Int, paragraph: Int) =
        dao.updateProgress(bookId, chapter, paragraph, System.currentTimeMillis())

    suspend fun importUri(uri: Uri): Long = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "book"
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw ImportException("无法读取文件")
        if (bytes.size > 50 * 1024 * 1024) throw ImportException("文件太大（超过 50 MB）")
        save(BookImporter.parse(bytes, name, resolver.getType(uri)))
    }

    suspend fun importText(title: String, text: String): Long = withContext(Dispatchers.IO) {
        if (text.isBlank()) throw ImportException("内容是空的")
        save(ParsedBook(title.ifBlank { "粘贴的文章" }, "", TxtImporter.splitChapters(text)))
    }

    suspend fun importSampleIfEmpty() = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        if (prefs.getBoolean("sampleImported", false)) return@withContext
        val text = context.assets.open("sample.txt").use { it.readBytes() }
        save(TxtImporter.parse(text, "示例：The Quiet Power of Reading.txt"))
        prefs.edit().putBoolean("sampleImported", true).apply()
    }

    private suspend fun save(book: ParsedBook): Long {
        val chapterWords = book.chapters.map { Tokenizer.countWords(it.text) }
        val id = dao.insert(
            BookEntity(
                title = book.title, author = book.author, chapterCount = book.chapters.size,
                wordCount = chapterWords.sum(), addedAt = System.currentTimeMillis(),
            )
        )
        val dir = bookDir(id).apply { mkdirs() }
        book.chapters.forEachIndexed { i, ch -> File(dir, "$i.txt").writeText(ch.text) }
        dao.insertChapters(book.chapters.mapIndexed { i, ch -> ChapterEntity(id, i, ch.title, chapterWords[i]) })
        return id
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        dao.delete(id)
        dao.deleteChapters(id)
        bookDir(id).deleteRecursively()
    }
}
