package com.readvocab.core

import com.readvocab.core.ai.AiTutor
import com.readvocab.core.ai.AiConfig
import com.readvocab.core.ai.OpenAiCompatibleProvider
import com.readvocab.core.ai.ProviderType
import com.readvocab.core.importer.EpubImporter
import com.readvocab.core.importer.HtmlText
import com.readvocab.core.importer.TxtImporter
import com.readvocab.core.srs.Fsrs
import com.readvocab.core.srs.MemoryState
import com.readvocab.core.srs.Rating
import com.readvocab.core.text.Lemmatizer
import com.readvocab.core.text.Tokenizer
import com.readvocab.core.vocab.Highlight
import com.readvocab.core.vocab.HighlightMode
import com.readvocab.core.vocab.TestItem
import com.readvocab.core.vocab.VocabEstimator
import com.readvocab.core.vocab.WordClassifier
import com.readvocab.core.vocab.WordStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CoreTest {
    @Test fun tokenizeKeepsApostrophes() {
        val t = Tokenizer.tokenize("He didn't run—she ran. It's John's well-known book.")
        assertEquals(listOf("He", "didn't", "run", "she", "ran", "It's", "John's", "well", "known", "book"), t.map { it.text })
        assertEquals("didn't", "He didn't run".substring(t[1].start, t[1].end))
    }

    @Test fun sentenceAroundOffset() {
        val p = "First one. The cat sat on the mat! \"Really?\" she asked. Last."
        assertEquals("The cat sat on the mat!", Tokenizer.sentenceAt(p, p.indexOf("cat")))
        assertEquals("First one.", Tokenizer.sentenceAt(p, 2))
        assertEquals("Last.", Tokenizer.sentenceAt(p, p.length - 2))
        val ranges = Tokenizer.sentenceRanges(p).map { p.substring(it.first, it.last + 1).trim() }
        assertEquals(listOf("First one.", "The cat sat on the mat!", "\"Really?\" she asked.", "Last."), ranges)
    }

    @Test fun lemmatizer() {
        val dict = setOf("run", "study", "stop", "make", "book", "happy", "walk")
        val table = mapOf("ran" to "run", "made" to "make")
        val l = Lemmatizer(table::get, dict::contains)
        assertEquals("run", l.lemma("Ran"))
        assertEquals("study", l.lemma("studies"))
        assertEquals("stop", l.lemma("stopped"))
        assertEquals("make", l.lemma("making"))
        assertEquals("book", l.lemma("books"))
        assertEquals("happy", l.lemma("happiest"))
        assertEquals("walk", l.lemma("walk’s"))
        assertEquals("xyzzy", l.lemma("xyzzy"))
    }

    @Test fun fsrsIntervalsGrow() {
        val f = Fsrs()
        val day = 86_400_000L
        var s = f.review(MemoryState(), Rating.Again, 0)
        assertTrue(s.due - 0 < day)
        var now = s.due
        val intervals = mutableListOf<Long>()
        repeat(5) {
            s = f.review(s, Rating.Good, now)
            intervals += s.due - now
            now = s.due
        }
        assertTrue("intervals should grow: $intervals", intervals.zipWithNext().all { (a, b) -> b > a })
        val failed = f.review(s, Rating.Again, now)
        assertTrue(failed.stability < s.stability)
        assertEquals(1, failed.lapses)
        assertEquals(WordStatus.LEARNING, WordStatus.fromMemory(MemoryState(stability = 1.0)))
    }

    @Test fun vocabEstimate() {
        val items = VocabEstimator.BANDS.indices.flatMap { b -> (0 until 4).map { TestItem("w$b-$it", b) } }
        // knows everything in the first 6 bands, half of band 6, nothing after
        val known = items.filter { it.band < 6 || (it.band == 6 && it.word.endsWith("0") || it.band == 6 && it.word.endsWith("1")) }.map { it.word }.toSet()
        assertEquals(6500 + 750, VocabEstimator.estimate(items, known))
        assertEquals(8000, VocabEstimator.knownBoundary(items, known))
    }

    @Test fun classifier() {
        assertEquals(Highlight.NONE, WordClassifier.highlight(500, null, 3000, HighlightMode.ALL))
        assertEquals(Highlight.NEW, WordClassifier.highlight(9000, null, 3000, HighlightMode.ALL))
        assertEquals(Highlight.NONE, WordClassifier.highlight(9000, null, 3000, HighlightMode.LEARNING_ONLY))
        assertEquals(Highlight.LEARNING, WordClassifier.highlight(500, WordStatus.LEARNING, 3000, HighlightMode.LEARNING_ONLY))
        assertEquals(Highlight.NONE, WordClassifier.highlight(null, null, 3000, HighlightMode.ALL))
        assertEquals(Highlight.NONE, WordClassifier.highlight(9000, WordStatus.KNOWN, 3000, HighlightMode.ALL))
    }

    @Test fun txtChapters() {
        val text = "Some preface.\n\nChapter 1\nIt was a dark night.\n\nChapter 2\nThe end came."
        val ch = TxtImporter.splitChapters(text)
        assertEquals(listOf("Chapter 1", "Chapter 2"), ch.map { it.title })
        assertEquals("It was a dark night.", ch[0].text)
        val gbk = "Hello 你好".toByteArray(charset("GB18030"))
        assertEquals("Hello 你好", TxtImporter.decode(gbk))
    }

    @Test fun epubParse() {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            fun put(name: String, s: String) { z.putNextEntry(ZipEntry(name)); z.write(s.toByteArray()); z.closeEntry() }
            put("mimetype", "application/epub+zip")
            put("META-INF/container.xml", """<?xml version="1.0"?><container><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""")
            put("OEBPS/content.opf", """<package><metadata><dc:title>My Book</dc:title><dc:creator>Jane Doe</dc:creator></metadata>
                <manifest><item id="c1" href="text/ch%201.xhtml" media-type="application/xhtml+xml"/><item id="css" href="s.css" media-type="text/css"/></manifest>
                <spine><itemref idref="c1"/></spine></package>""")
            put("OEBPS/text/ch 1.xhtml", """<html><head><title>x</title><style>p{}</style></head><body><h1>Chapter One</h1><p>It&#8217;s a truth universally acknowledged &amp; more.</p><p>Second&nbsp;paragraph here.</p></body></html>""")
        }
        val book = EpubImporter.parse(bos.toByteArray(), "x.epub")
        assertEquals("My Book", book.title)
        assertEquals("Jane Doe", book.author)
        assertEquals("Chapter One", book.chapters.single().title)
        assertTrue(book.chapters.single().text.contains("It’s a truth universally acknowledged & more."))
        assertTrue(book.chapters.single().text.contains("Second paragraph here."))
        assertEquals("a < b", HtmlText.decodeEntities("a &lt; b"))
    }

    @Test fun aiJsonExtraction() {
        val raw = "Sure!\n```json\n{\"meaning\": \"跑 {x}\", \"pos\": \"v.\"}\n```"
        assertEquals("{\"meaning\": \"跑 {x}\", \"pos\": \"v.\"}", AiTutor.extractJsonObject(raw))
        val p = OpenAiCompatibleProvider(AiConfig(ProviderType.OPENAI_COMPATIBLE, "k", "m"))
        assertTrue(p.buildRequestBody("s", "u\"q", 10).contains("\"content\":\"u\\\"q\""))
        assertEquals("hi", p.parseResponse("""{"choices":[{"message":{"role":"assistant","content":"hi"}}]}"""))
    }
}
