package com.readvocab.app

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.readvocab.app.data.AppDatabase
import com.readvocab.app.data.Fetcher
import com.readvocab.app.data.NewsRepository
import com.readvocab.app.ui.NavRequest
import com.readvocab.app.ui.browser.normalizeAddress
import com.readvocab.core.importer.ImportException
import com.readvocab.core.vocab.WordStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class NewsTest {
    private val app = ApplicationProvider.getApplicationContext<ReadVocabApp>()
    private val c get() = app.container
    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    /** Output of the real extraction script, produced in Chromium by tools/extract-test.js. */
    @Test fun importsExtractedArticle() = runBlocking {
        c.dictionary.ensureLoaded()
        val article = c.articles.parseResult(fixture("article-page.extracted.json"))
        assertEquals("Cities Turn to Rooftop Farms as Summers Grow Hotter", article.title)
        assertEquals("Jane Doe", article.byline)
        assertTrue(article.text.startsWith("On a flat roof six storeys"))
        assertFalse("caption dropped", article.text.contains("Photo: Example Agency"))
        assertFalse("promo dropped", article.text.contains("Sign up"))
        assertFalse("navigation dropped", article.text.contains("Business"))
        assertTrue(article.text.trimEnd().endsWith("continue to get hotter.\""))
        assertEquals(5, article.text.split("\n\n").size)
        assertEquals("an excerpt that repeats the first paragraph is not a standfirst", "", article.excerpt)

        val id = c.articles.save(article)
        val book = c.books.get(id)!!
        assertTrue(book.isArticle)
        assertEquals("Example News · Jane Doe", book.author)
        assertEquals(article.url, book.sourceUrl)
        assertTrue(c.books.chapterText(id, 0).startsWith("# Cities Turn to Rooftop Farms"))
        assertEquals("same page is not imported twice", id, c.articles.save(article))

        // Coverage: an ordinary news text is mostly known at a 5000-word level.
        c.settings.update { it.copy(knownBoundary = 5000) }
        val cov = c.coverage.ofText(article.text)!!
        assertTrue("coverage $cov", cov in 0.85..0.995)
        val byBook = c.coverage.observe().first()
        assertEquals(cov, byBook[id]!!, 0.02)
        assertTrue(File(app.filesDir, "books/$id/lemmas-v3.tsv").exists())
        // Learning a word from the article lowers its coverage.
        val unknown = article.text.split(Regex("\\W+")).map { c.dictionary.lemma(it) }.first { (c.dictionary.rank(it) ?: 0) in 1..5000 }
        c.words.setStatus(unknown, WordStatus.LEARNING)
        assertTrue(c.coverage.observe().first()[id]!! < byBook[id]!!)
    }

    /** A page shaped like a BBC article: byline box, image credits, subheadings, a list, an ad slot. */
    @Test fun keepsArticleShape() {
        val a = c.articles.parseResult(fixture("bbc-like.extracted.json"))
        assertEquals("Why river towns are planting forests upstream", a.title)
        val paras = a.text.split("\n\n")
        assertEquals("For the second time in three years, shopkeepers in a small river town spent the new year pumping water out of their cellars.", paras[0])
        assertTrue("## A cheaper defence?" in paras)
        assertTrue("\u2022 restoring old wetlands near the river," in paras)
        for (junk in listOf("Share", "Save", "Getty Images", "Image caption", "Alex Rivers", "Advertisement", "More on this story", "17 November")) {
            assertFalse("should drop $junk", a.text.contains(junk))
        }
        assertTrue(paras.last().startsWith("Similar schemes"))
        assertEquals(11, paras.size)
        assertEquals("Instead of higher walls, one town will pay farmers up the valley to plant trees.", a.excerpt)
        assertEquals(java.time.Instant.parse("2026-11-17T06:12:00Z").toEpochMilli(), a.publishedAt)
    }

    @Test fun frontPageIsRejected() {
        try {
            c.articles.parseResult(fixture("front-page.extracted.json"))
            fail("a front page has no article")
        } catch (e: ImportException) {
            assertTrue(e.message!!.contains("正文"))
        }
        try {
            c.articles.parseResult("\"null\"")
            fail()
        } catch (_: ImportException) {
        }
        try {
            c.articles.parseResult(null)
            fail()
        } catch (_: ImportException) {
        }
    }

    @Test fun sharedTextAndAddresses() {
        assertEquals(NavRequest.OpenUrl("https://www.bbc.com/news/articles/abc"), NavRequest.fromSharedText("Big story https://www.bbc.com/news/articles/abc"))
        assertEquals(NavRequest.OpenUrl("https://www.newyorker.com/x"), NavRequest.fromSharedText("https://www.newyorker.com/x"))
        val passage = "It was the best of times. ".repeat(30)
        val r = NavRequest.fromSharedText(passage, "Dickens")
        assertTrue(r is NavRequest.SharedText && r.title == "Dickens")
        assertEquals(null, NavRequest.fromSharedText(""))
        assertEquals("https://bbc.com/news", normalizeAddress("bbc.com/news"))
        assertEquals("http://example.com", normalizeAddress("http://example.com"))
        assertTrue(normalizeAddress("climate change news").startsWith("https://www.bing.com/search?q=climate+change"))
    }

    @Test fun refreshKeepsOldItemsWhenAFeedFails() = runBlocking {
        val rss = """<rss><channel><title>T</title>
            <item><title>One</title><link>https://example.com/1</link><pubDate>Tue, 17 Nov 2026 09:30:00 GMT</pubDate></item>
            <item><title>Two</title><link>https://example.com/2</link><pubDate>Tue, 17 Nov 2026 08:30:00 GMT</pubDate></item>
            </channel></rss>"""
        var down = false
        val fetcher = Fetcher { url ->
            when {
                down -> throw IOException("timeout")
                url.contains("bbci") -> rss.toByteArray()
                else -> throw IOException("blocked")
            }
        }
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val news = NewsRepository(db, fetcher = fetcher)
        val now = java.time.Instant.parse("2026-11-17T10:00:00Z").toEpochMilli()
        val r = news.refresh(now)
        // The three BBC feeds share the fake response; other default feeds fail.
        assertEquals(
            listOf(
                "The New Yorker", "The Economist · The world this week", "The Economist · Leaders",
                "The Economist · Science & technology", "NYT · World", "NYT · Technology",
            ),
            r.failed,
        )
        val items = news.observeItems().first()
        assertEquals(listOf("One", "Two"), items.map { it.title })
        down = true
        news.refresh(now + 60_000)
        assertEquals(2, news.observeItems().first().size)
        assertNotNull(news.observeFeeds().first().first { it.title == "BBC News · World" }.lastError)

        news.setEnabled(NewsRepository.DEFAULT_FEEDS[0].url, false)
        news.setEnabled(NewsRepository.DEFAULT_FEEDS[1].url, false)
        news.setEnabled(NewsRepository.DEFAULT_FEEDS[2].url, false)
        assertTrue("items of disabled feeds are hidden", news.observeItems().first().isEmpty())
        db.close()
    }

    /** Feeds added to the built-in list in an update reach existing users once; removed ones stay removed. */
    @Test fun newBuiltInFeedsReachExistingLists() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val news = NewsRepository(db, settings = c.settings)
        c.settings.update { it.copy(feedDefaults = 0) }
        val economist = "https://www.economist.com/leaders/rss.xml"
        // An install from before the update: the first set of feeds, minus a feed the user removed.
        val firstSet = NewsRepository.DEFAULT_FEEDS.filter { f -> NewsRepository.ADDED_FEEDS.values.flatten().none { it == f.url } }
        db.feeds().insertFeeds(firstSet.drop(1))
        news.ensureDefaults()
        val urls = news.observeFeeds().first().map { it.url }
        assertTrue(economist in urls)
        assertTrue("https://www.newyorker.com/feed/everything" in urls)
        assertFalse(firstSet[0].url in urls)
        assertEquals(NewsRepository.DEFAULTS_VERSION, c.settings.current.feedDefaults)
        news.removeFeed(economist)
        news.ensureDefaults()
        assertFalse("a removed feed is not added back", economist in news.observeFeeds().first().map { it.url })
        db.close()
    }

    @Test fun addFeedValidates() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val news = NewsRepository(db) { url ->
            if (url.endsWith("good.xml")) "<rss><channel><title>Good Feed</title><item><title>A</title><link>https://g.com/a</link></item></channel></rss>".toByteArray()
            else "<html>nope</html>".toByteArray()
        }
        assertEquals("Good Feed", news.addFeed("g.com/good.xml").title)
        try {
            news.addFeed("https://g.com/page.html")
            fail()
        } catch (_: Exception) {
        }
        db.close()
    }

    @Test fun migrates3toLatest() = runBlocking {
        val schema = JSONObject(File("schemas/com.readvocab.app.data.AppDatabase/3.json").readText()).getJSONObject("database")
        val file = app.getDatabasePath("migrate-34.db").apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                e.optJSONArray("indices")?.let { idx ->
                    for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO books (title, author, chapterCount, wordCount, addedAt, lastReadAt, chapterIndex, paragraphIndex, kind) VALUES ('Old', '', 1, 10, 1, 0, 0, 0, 'BOOK')")
            db.version = 3
        }
        val room = Room.databaseBuilder(app, AppDatabase::class.java, file.path)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS).build()
        assertEquals("", room.books().get(1)!!.sourceUrl)
        assertEquals("", room.books().get(1)!!.excerpt)
        assertEquals(0L, room.books().get(1)!!.publishedAt)
        assertTrue(room.feeds().enabledFeeds().isEmpty())
        room.close()
    }
}
