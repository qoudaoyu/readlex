package com.readvocab.core

import com.readvocab.core.news.ArticleBlock
import com.readvocab.core.news.ArticleText
import com.readvocab.core.text.ParaMarkup
import com.readvocab.core.news.FeedException
import com.readvocab.core.news.FeedParser
import com.readvocab.core.vocab.Coverage
import com.readvocab.core.vocab.WordStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsTest {
    @Test fun parsesRss() {
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/"><channel>
              <title>Example News - World</title>
              <item>
                <title><![CDATA[Storm hits the coast &amp; ports close]]></title>
                <link>https://example.com/news/storm</link>
                <description><![CDATA[<p>Ships were told to <b>stay</b> in harbour.</p>]]></description>
                <pubDate>Tue, 17 Nov 2026 09:30:00 GMT</pubDate>
              </item>
              <item><title>No link here</title></item>
              <item>
                <title>Second</title><guid>https://example.com/news/2</guid>
                <dc:date>2026-11-16T08:00:00Z</dc:date>
              </item>
            </channel></rss>""".trimIndent()
        val feed = FeedParser.parse(xml.toByteArray())
        assertEquals("Example News - World", feed.title)
        assertEquals(2, feed.items.size)
        val first = feed.items[0]
        assertEquals("Storm hits the coast & ports close", first.title)
        assertEquals("https://example.com/news/storm", first.link)
        assertEquals("Ships were told to stay in harbour.", first.summary)
        assertEquals(java.time.Instant.parse("2026-11-17T09:30:00Z").toEpochMilli(), first.publishedAt)
        assertEquals("https://example.com/news/2", feed.items[1].link)
        assertEquals(java.time.Instant.parse("2026-11-16T08:00:00Z").toEpochMilli(), feed.items[1].publishedAt)
    }

    @Test fun parsesAtom() {
        val xml = """<?xml version="1.0"?>
            <feed xmlns="http://www.w3.org/2005/Atom"><title>Example Blog</title>
              <entry><title>Hello</title>
                <link rel="alternate" href="https://example.com/hello"/>
                <link rel="enclosure" href="https://example.com/a.mp3"/>
                <summary>Short summary</summary><updated>2026-11-15T10:00:00+01:00</updated>
              </entry>
            </feed>""".trimIndent()
        val feed = FeedParser.parse(xml.toByteArray())
        assertEquals("Example Blog", feed.title)
        assertEquals("https://example.com/hello", feed.items.single().link)
        assertEquals(java.time.Instant.parse("2026-11-15T09:00:00Z").toEpochMilli(), feed.items.single().publishedAt)
    }

    @Test fun rejectsHtmlAndExternalEntities() {
        for (bad in listOf(
            "<html><body>Not a feed",
            """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><rss><channel><item><title>&x;</title><link>https://a.b</link></item></channel></rss>""",
        )) {
            try {
                val items = FeedParser.parse(bad.toByteArray()).items
                assertFalse(items.any { it.title.contains("root:") })
            } catch (_: FeedException) {
            }
        }
    }

    @Test fun rfc822Dates() {
        assertEquals(java.time.Instant.parse("2026-11-17T14:30:00Z").toEpochMilli(), FeedParser.parseDate("Tue, 17 Nov 2026 09:30:00 EST"))
        assertEquals(java.time.Instant.parse("2026-11-17T09:30:00Z").toEpochMilli(), FeedParser.parseDate("Tue, 17 Nov 2026 09:30:00 +0000"))
        assertEquals(0, FeedParser.parseDate("yesterday"))
    }

    @Test fun articleTextDropsCaptionsAndPromos() {
        val html = """<div><figure><img src="x.jpg"><figcaption>Photo: Agency</figcaption></figure>
            <p>First paragraph of the story.</p><p>Sign up for our newsletter</p>
            <p>Readers who subscribe to the paper said they liked it.</p></div>"""
        assertEquals("First paragraph of the story.\n\nReaders who subscribe to the paper said they liked it.", ArticleText.fromHtml(html))
    }

    @Test fun articleBlocksKeepShape() {
        val blocks = listOf(
            ArticleBlock("h", "Why river towns are planting forests"),   // repeats the headline
            ArticleBlock("p", "Share"),
            ArticleBlock("p", "First   paragraph,\n  split over\n lines."),
            ArticleBlock("h", "A cheaper defence?"),
            ArticleBlock("li", "planting trees,"),
            ArticleBlock("q", "We can't stop the rain."),
            ArticleBlock("p", "Advertisement"),
            ArticleBlock("h", "More on this story"),
        )
        assertEquals(
            "First paragraph, split over lines.\n\n## A cheaper defence?\n\n\u2022 planting trees,\n\n> We can't stop the rain.",
            ArticleText.fromBlocks(blocks, "Why river towns are planting forests"),
        )
        // HTML fallback: line breaks inside a <p> are just spaces.
        assertEquals("One two three.\n\nFour.", ArticleText.fromHtml("<p>One\n two\n  three.</p>\n<p>Four.</p>"))
        assertEquals(ParaMarkup.Kind.TITLE to "Headline", ParaMarkup.parse("# Headline"))
        assertEquals(ParaMarkup.Kind.HEADING to "Sub", ParaMarkup.parse("## Sub"))
        assertEquals(ParaMarkup.Kind.BODY to "#hashtag", ParaMarkup.parse("#hashtag"))
    }

    @Test fun coverage() {
        val counts = Coverage.lemmaCounts("The cat sat. The cat ran away from Bob.") { it.lowercase() }
        assertEquals(2, counts["cat"])
        val ranks = mapOf("the" to 1, "cat" to 3000, "sit" to 800, "sat" to 800, "ran" to 500, "away" to 300, "from" to 20)
        // "bob" is not a dictionary word and is left out; "cat" is beyond the boundary.
        val cov = Coverage.of(counts) { Coverage.isKnown(ranks[it], null, 2000) }!!
        assertEquals(6.0 / 8, cov, 1e-9)
        // Learning the word counts as unknown, having it in long-term memory as known.
        assertEquals(false, Coverage.isKnown(100, WordStatus.LEARNING, 2000))
        assertEquals(true, Coverage.isKnown(9000, WordStatus.FAMILIAR, 2000))
        assertNull(Coverage.isKnown(null, null, 2000))
        assertNull(Coverage.of(mapOf("bob" to 1)) { null })
        assertEquals(Coverage.Level.JUST_RIGHT, Coverage.level(0.96))
        assertEquals(Coverage.Level.HARD, Coverage.level(0.85))
        assertTrue(Coverage.level(0.99) == Coverage.Level.EASY)
    }

}
