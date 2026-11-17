package com.readvocab.app.data

import com.readvocab.core.news.FeedException
import com.readvocab.core.news.FeedParser
import com.readvocab.core.news.ParsedFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** A site the in-app browser offers on its start page. */
data class SiteBookmark(val name: String, val url: String)

/** Fetches a URL's bytes; swapped out in tests. */
fun interface Fetcher {
    fun fetch(url: String): ByteArray
}

object HttpFetcher : Fetcher {
    private const val MAX_BYTES = 5 * 1024 * 1024

    override fun fetch(url: String): ByteArray {
        var target = URL(url)
        repeat(5) {
            val conn = (target.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) ReadVocab/1.0")
                setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml, */*")
            }
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location") ?: throw IOException("重定向缺少地址")
                    target = URL(target, loc)
                    return@repeat
                }
                if (code !in 200..299) throw IOException("服务器返回 $code")
                conn.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_BYTES) throw IOException("订阅源太大")
                    }
                    return out.toByteArray()
                }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("重定向次数太多")
    }
}

data class RefreshResult(val newItems: Int, val failed: List<String>)

/** News tab: the user's feeds and the latest headlines from them, cached for offline viewing. */
class NewsRepository(private val db: AppDatabase, private val fetcher: Fetcher = HttpFetcher) {
    private val dao = db.feeds()

    fun observeFeeds(): Flow<List<FeedEntity>> = dao.observeFeeds()
    fun observeItems(): Flow<List<FeedItemEntity>> = dao.observeItems()

    suspend fun ensureDefaults() {
        if (dao.countFeeds() == 0) dao.insertFeeds(DEFAULT_FEEDS.mapIndexed { i, f -> f.copy(position = i) })
    }

    /** Fetches all enabled feeds in parallel; a feed that fails keeps its old items. */
    suspend fun refresh(now: Long = System.currentTimeMillis()): RefreshResult = coroutineScope {
        ensureDefaults()
        val feeds = dao.enabledFeeds()
        val results = feeds.map { feed ->
            async(Dispatchers.IO) {
                try {
                    val parsed = FeedParser.parse(fetcher.fetch(feed.url))
                    val items = parsed.items.take(MAX_ITEMS_PER_FEED).map {
                        FeedItemEntity(it.link, feed.url, it.title, it.summary, it.publishedAt.takeIf { t -> t > 0 } ?: now, now)
                    }
                    dao.upsertItems(items)
                    dao.markFetched(feed.url, now, null)
                    items.size to null
                } catch (e: Exception) {
                    val msg = when (e) {
                        is FeedException -> e.message
                        is IOException -> "连不上：${e.message}"
                        else -> e.message
                    } ?: "失败"
                    dao.markFetched(feed.url, feed.lastFetchedAt, msg)
                    0 to feed.title
                }
            }
        }.awaitAll()
        dao.prune(now - KEEP_DAYS * 24 * 3600 * 1000L)
        RefreshResult(results.sumOf { it.first }, results.mapNotNull { it.second })
    }

    /** Validates a feed URL by fetching it, then adds it under its own title. */
    suspend fun addFeed(url: String): FeedEntity = withContext(Dispatchers.IO) {
        val clean = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
        val parsed: ParsedFeed = try {
            FeedParser.parse(fetcher.fetch(clean))
        } catch (e: IOException) {
            throw FeedException("连不上这个地址：${e.message}")
        }
        if (parsed.items.isEmpty()) throw FeedException("这个订阅源里没有文章")
        val feed = FeedEntity(clean, parsed.title.ifBlank { URL(clean).host }, position = 1000)
        dao.upsertFeed(feed)
        val now = System.currentTimeMillis()
        dao.upsertItems(parsed.items.take(MAX_ITEMS_PER_FEED).map {
            FeedItemEntity(it.link, clean, it.title, it.summary, it.publishedAt.takeIf { t -> t > 0 } ?: now, now)
        })
        dao.markFetched(clean, now, null)
        feed
    }

    suspend fun setEnabled(url: String, enabled: Boolean) = dao.setEnabled(url, enabled)

    suspend fun removeFeed(url: String) {
        dao.deleteFeed(url)
        dao.deleteItems(url)
    }

    companion object {
        private const val MAX_ITEMS_PER_FEED = 40
        private const val KEEP_DAYS = 14

        val DEFAULT_FEEDS = listOf(
            FeedEntity("https://feeds.bbci.co.uk/news/world/rss.xml", "BBC News · World", "https://www.bbc.com/news"),
            FeedEntity("https://feeds.bbci.co.uk/news/technology/rss.xml", "BBC News · Technology", "https://www.bbc.com/news/technology"),
            FeedEntity("https://feeds.bbci.co.uk/news/science_and_environment/rss.xml", "BBC News · Science", "https://www.bbc.com/news/science_and_environment"),
            FeedEntity("https://www.newyorker.com/feed/everything", "The New Yorker", "https://www.newyorker.com"),
            FeedEntity("https://rss.nytimes.com/services/xml/rss/nyt/World.xml", "NYT · World", "https://www.nytimes.com/section/world"),
            FeedEntity("https://rss.nytimes.com/services/xml/rss/nyt/Technology.xml", "NYT · Technology", "https://www.nytimes.com/section/technology"),
            FeedEntity("https://www.theguardian.com/world/rss", "The Guardian · World", "https://www.theguardian.com/world", enabled = false),
            FeedEntity("https://time.com/feed/", "TIME", "https://time.com", enabled = false),
        )

        val BOOKMARKS = listOf(
            SiteBookmark("BBC News", "https://www.bbc.com/news"),
            SiteBookmark("The New Yorker", "https://www.newyorker.com"),
            SiteBookmark("The New York Times", "https://www.nytimes.com"),
            SiteBookmark("The Times", "https://www.thetimes.com"),
            SiteBookmark("TIME", "https://time.com"),
            SiteBookmark("The Guardian", "https://www.theguardian.com/international"),
            SiteBookmark("The Atlantic", "https://www.theatlantic.com"),
            SiteBookmark("The Economist", "https://www.economist.com"),
        )
    }
}
