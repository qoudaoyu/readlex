package com.readvocab.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.readvocab.app.data.AppDatabase
import com.readvocab.app.data.ArticleCheckEntity
import com.readvocab.app.data.ArticleChecker
import com.readvocab.app.data.FeedEntity
import com.readvocab.app.data.FeedItemEntity
import com.readvocab.app.data.PageReader
import com.readvocab.app.data.WebPageReader
import com.readvocab.core.vocab.Coverage
import com.readvocab.core.vocab.KnownCurve
import com.readvocab.core.vocab.WordStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class DifficultyAppTest {
    private val app = ApplicationProvider.getApplicationContext<ReadVocabApp>()
    private val c get() = app.container
    private fun fixture(name: String) = javaClass.classLoader!!.getResource(name)!!.readText()

    /** What the extraction script returns for an article made of [paragraphs]. */
    private fun extracted(title: String, paragraphs: List<String>): String {
        val blocks = JSONArray()
        paragraphs.forEach { blocks.put(JSONObject().put("type", "p").put("text", it)) }
        val o = JSONObject().put("title", title).put("siteName", "Example").put("blocks", blocks).put("url", "https://example.com/$title")
        return JSONObject.quote(o.toString())
    }

    @Test fun familyRanksFromTheRealDictionary() = runBlocking {
        val d = c.dictionary
        d.ensureLoaded()
        assertEquals(d.rank("defense"), d.rank("defence"))
        assertEquals(minOf(d.rank("color")!!, d.rank("colour")!!), d.rank("colour"))
        assertEquals(d.rank("neighbor"), d.rank("neighboring"))
        assertTrue("neighbouring ${d.rank("neighbouring")}", d.rank("neighbouring")!! < 6000)
        assertTrue("jointly ${d.rank("jointly")}", d.rank("jointly")!! < 4000)
        assertTrue("organisation ${d.rank("organisation")}", d.rank("organisation")!! < 1500)
        // Look-alikes outside a family keep their own rank.
        assertTrue("liver ${d.rank("liver")}", d.rank("liver")!! > 2000)
        assertTrue("ear ${d.rank("early")}", d.rank("early")!! < 1000)
    }

    /** A British and an American version of the same text are equally hard. */
    @Test fun britishSpellingIsNotHarder() = runBlocking {
        c.dictionary.ensureLoaded()
        c.settings.update { it.copy(knownBoundary = 4000, knownCurve = "", difficultyShift = 0f) }
        val uk = "The organisation said the defence of the neighbouring centre would cost a fraction of the programme, and the colour of the labour market had changed."
        val us = "The organization said the defense of the neighboring center would cost a fraction of the program, and the color of the labor market had changed."
        assertEquals(c.coverage.ofText(us)!!, c.coverage.ofText(uk)!!, 0.005)
    }

    @Test fun checksArticlesAndRecommends() = runBlocking {
        c.dictionary.ensureLoaded()
        c.settings.update { it.copy(knownBoundary = 5000, knownCurve = "", difficultyShift = 0f) }
        val sample = app.assets.open("sample.txt").bufferedReader().readText()
            .split(Regex("\n\\s*\n")).map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.split(" ").size > 20 }
        val now = System.currentTimeMillis()
        val pages = mapOf(
            "https://example.com/long" to extracted("long", sample + sample),
            "https://example.com/teaser" to extracted("teaser", sample.take(1)),
            "https://example.com/bbc" to fixture("bbc-like.extracted.json"),
        )
        val reader = PageReader { url -> pages[url] ?: throw IOException("offline") }
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val checker = ArticleChecker(db, reader, c.articles, c.dictionary, c.words, c.coverage, c.settings)
        db.feeds().insertFeeds(listOf(FeedEntity("https://example.com/feed", "Example · World", "https://example.com")))
        db.feeds().upsertItems(
            (pages.keys + "https://example.com/down" + "https://example.com/old").map { link ->
                FeedItemEntity(link, "https://example.com/feed", link.substringAfterLast('/'), "", if (link.endsWith("old")) now - 5L * 24 * 3600_000 else now - 3600_000, now)
            },
        )
        assertEquals(4, checker.checkPending(now))
        assertEquals("nothing left to check", 0, checker.checkPending(now))
        val d = checker.observe().first()
        assertEquals(ArticleCheckEntity.OK, d["https://example.com/long"]!!.status)
        assertTrue(d["https://example.com/long"]!!.words >= 600)
        assertEquals(ArticleCheckEntity.SHORT, d["https://example.com/teaser"]!!.status)
        assertEquals(ArticleCheckEntity.FAILED, d["https://example.com/down"]!!.status)
        assertNull("too old to check", d["https://example.com/old"])
        val cov = d["https://example.com/long"]!!.coverage!!
        // The stored counts give the same coverage as measuring the text directly.
        val text = (sample + sample).joinToString("\n\n")
        assertEquals(c.coverage.ofText(text)!!, cov, 0.0005)

        // Learning words moves the estimate without reading the article again.
        val unknown = Coverage.lemmaCounts(text) { c.dictionary.lemma(it) }.keys
            .filter { (c.dictionary.rank(it) ?: 0) > 6000 }.take(3)
        unknown.forEach { c.words.setStatus(it, WordStatus.KNOWN) }
        assertTrue(checker.observe().first()["https://example.com/long"]!!.coverage!! > cov)

        // Recommendations need enough text and a suitable level; imported items are left out.
        val items = db.feeds().observeItems().first()
        val level = { cv: Double -> Coverage.level(cv) }
        val rec = checker.recommend(items, checker.observe().first(), { "Example" }, emptySet(), now)
        val longCov = checker.observe().first()["https://example.com/long"]!!.coverage!!
        if (longCov >= 0.90) assertEquals(listOf("https://example.com/long"), rec.map { it.link }) else assertTrue(rec.isEmpty())
        assertTrue(checker.recommend(items, checker.observe().first(), { "Example" }, setOf("https://example.com/long"), now).isEmpty())
        assertFalse(level(longCov).name.isEmpty())
        db.close()
    }

    @Test fun pageScriptsAreRemovedButMetadataKept() {
        val html = """<html><head><script>alert(1)</script><script type="application/ld+json">{"headline":"x"}</script></head><body><p>Hi</p><SCRIPT src="a.js"></SCRIPT></body></html>"""
        val out = WebPageReader.stripScripts(html)
        assertFalse(out.contains("alert"))
        assertFalse(out.contains("a.js"))
        assertTrue(out.contains("application/ld+json"))
        assertTrue(out.contains("<p>Hi</p>"))
    }

    @Test fun testCurveIsUsedOnceTaken() {
        val curve = KnownCurve.fromTest(listOf(1.0, 1.0, 1.0, 1.0, 0.5, 0.33, 0.17, 0.0, 0.0, 0.0, 0.0, 0.0), 0.0)
        c.settings.update { it.copy(knownCurve = curve.encode(), difficultyShift = 0.2f) }
        val s = c.settings.current.curve
        assertEquals(curve.withShift(0.2).probability(6000), s.probability(6000), 0.002)
        c.settings.update { it.copy(knownCurve = "", difficultyShift = 0f) }
    }
}
