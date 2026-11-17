package com.readvocab.app.data

import android.content.Context
import com.readvocab.core.importer.ImportException
import com.readvocab.core.news.ArticleText
import com.readvocab.core.text.Tokenizer
import org.json.JSONObject
import java.net.URL

/** What Readability found on a page. */
data class ExtractedArticle(val title: String, val byline: String, val siteName: String, val text: String, val url: String) {
    val wordCount: Int get() = Tokenizer.countWords(text)
}

/**
 * Imports the article on a web page. Extraction runs inside the in-app WebView (so pages that
 * need scripts or a signed-in subscription work), using Mozilla's Readability, the engine behind
 * Firefox Reader View; this class builds that script and turns its output into a book.
 */
class ArticleImporter(private val context: Context, private val books: BookRepository) {
    private val script: String by lazy {
        fun asset(name: String) = context.assets.open("readability/$name").use { it.readBytes().decodeToString() }
        asset("extract.js").replace("/*READABILITY*/", asset("Readability.js"))
    }

    /** JavaScript that returns the parsed article as a JSON string, or "null" (see assets/readability/extract.js). */
    fun extractionScript(): String = script

    /** Parses what [extractionScript] returned through `evaluateJavascript` (a JSON-encoded string). */
    fun parseResult(raw: String?): ExtractedArticle {
        val inner = raw?.let { runCatching { org.json.JSONTokener(it).nextValue() }.getOrNull() } as? String
        if (inner == null || inner == "null") throw ImportException("这一页没找到文章正文。打开具体的文章页再试。")
        val o = JSONObject(inner)
        if (o.has("error")) throw ImportException("提取正文失败：${o.getString("error")}")
        val text = ArticleText.fromHtml(o.optString("content"))
        val article = ExtractedArticle(
            title = o.optString("title").trim(),
            byline = o.optString("byline").trim(),
            siteName = o.optString("siteName").trim(),
            text = text,
            url = o.optString("url"),
        )
        if (article.wordCount < MIN_WORDS) {
            throw ImportException("只找到 ${article.wordCount} 个词的正文。可能需要登录或订阅才能看到全文，或者这不是文章页。")
        }
        return article
    }

    /** Saves the article; an article already imported from the same URL is reused. */
    suspend fun save(article: ExtractedArticle): Long {
        books.bySourceUrl(article.url)?.let { return it.id }
        val site = article.siteName.ifBlank { runCatching { URL(article.url).host.removePrefix("www.") }.getOrDefault("") }
        val author = listOf(site, article.byline).filter { it.isNotBlank() }.joinToString(" · ")
        val title = article.title.ifBlank { site }
        return books.importText(title, "$title\n\n${article.text}", author, BookEntity.KIND_ARTICLE, article.url)
    }

    companion object {
        const val MIN_WORDS = 120
    }
}
