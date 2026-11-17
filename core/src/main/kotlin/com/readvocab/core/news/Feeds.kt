package com.readvocab.core.news

import com.readvocab.core.importer.HtmlText
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.DocumentBuilderFactory

data class FeedItem(val title: String, val link: String, val summary: String, val publishedAt: Long)

data class ParsedFeed(val title: String, val items: List<FeedItem>)

class FeedException(message: String) : Exception(message)

/** Parses RSS 2.0 and Atom feeds into plain items. */
object FeedParser {
    fun parse(bytes: ByteArray): ParsedFeed {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // Feeds are untrusted input: no external entities or DTDs.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            isExpandEntityReferences = false
        }
        val doc = try {
            factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            throw FeedException("不是有效的 RSS/Atom 订阅源")
        }
        val root = doc.documentElement ?: throw FeedException("订阅源是空的")
        val atomFeed = root.tagName.lowercase() == "feed"
        val items = if (atomFeed) root.children("entry").map { atom(it) } else root.descendants("item").map { rss(it) }
        val title = if (atomFeed) root.text("title") else root.children("channel").firstOrNull()?.text("title").orEmpty()
        return ParsedFeed(clean(title), items.filter { it.title.isNotBlank() && it.link.startsWith("http") })
    }

    private fun rss(e: Element) = FeedItem(
        title = clean(e.text("title")),
        link = e.text("link").trim().ifBlank { e.text("guid").trim() },
        summary = clean(e.text("description")),
        publishedAt = parseDate(e.text("pubDate").ifBlank { e.text("dc:date") }),
    )

    private fun atom(e: Element): FeedItem {
        val links = e.children("link")
        val href = (links.firstOrNull { it.getAttribute("rel").let { r -> r.isEmpty() || r == "alternate" } } ?: links.firstOrNull())
            ?.getAttribute("href").orEmpty()
        return FeedItem(
            title = clean(e.text("title")),
            link = href.trim(),
            summary = clean(e.text("summary").ifBlank { e.text("content") }),
            publishedAt = parseDate(e.text("published").ifBlank { e.text("updated") }),
        )
    }

    private fun clean(s: String) = HtmlText.toText(s).replace(Regex("\\s+"), " ").trim().take(400)

    fun parseDate(s: String): Long {
        val t = s.trim()
        if (t.isEmpty()) return 0
        return runCatching { ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }
            .recoverCatching {
                // RFC 822 with a zone name such as "EST" or "GMT".
                ZonedDateTime.parse(t, DateTimeFormatter.ofPattern("EEE, d MMM yyyy HH:mm:ss zzz", java.util.Locale.US)).toInstant().toEpochMilli()
            }
            .getOrDefault(0)
    }

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

    private fun Element.descendants(tag: String): List<Element> {
        val nl = getElementsByTagName(tag)
        return (0 until nl.length).map { nl.item(it) as Element }
    }

    private fun Element.text(tag: String): String = children(tag).firstOrNull()?.textContent.orEmpty()
}

/** One block of an extracted article: "h" subheading, "p" paragraph, "li" list item, "q" quotation. */
data class ArticleBlock(val type: String, val text: String)

/** Turns what Readability extracts from an article page into reader paragraphs (see [ParaMarkup]). */
object ArticleText {
    private val DROP = Regex("(?is)<(figure|figcaption|button|aside|form|noscript|iframe)\\b.*?</\\1\\s*>")
    private val WS = Regex("\\s+")

    /** Fallback when only HTML is available: whitespace inside a paragraph is not a line break. */
    fun fromHtml(html: String): String {
        val collapsed = WS.replace(DROP.replace(html, ""), " ")
        return HtmlText.toText(collapsed).lines().map { ArticleBlock("p", it) }.let(::fromBlocks)
    }

    fun fromBlocks(blocks: List<ArticleBlock>, title: String = ""): String {
        val normTitle = norm(title)
        val out = ArrayList<String>()
        for (b in blocks) {
            val t = WS.replace(b.text, " ").trim()
            if (t.isEmpty() || isBoilerplate(t)) continue
            // The headline is shown separately; drop it when the body repeats it.
            if (out.isEmpty() && normTitle.isNotEmpty() && norm(t) == normTitle) continue
            out += when (b.type) {
                "h" -> "## $t"
                "li" -> "• $t"
                "q" -> "> $t"
                else -> t
            }
        }
        // A trailing subheading with nothing under it ("More on this story") is navigation.
        while (out.isNotEmpty() && out.last().startsWith("## ")) out.removeAt(out.lastIndex)
        return out.joinToString("\n\n")
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    /** Short promo, credit and widget lines that page templates put among the paragraphs. */
    private fun isBoilerplate(line: String): Boolean {
        if (line.length >= 120) return false
        val l = line.lowercase()
        return EXACT.contains(l.trimEnd('.', ':', ',')) || PREFIXES.any { l.startsWith(it) }
    }

    private val EXACT = setOf(
        "advertisement", "share", "save", "share this article", "listen", "watch", "related", "related topics",
        "more on this story", "read more", "image source", "image caption", "getty images", "skip advertisement",
        "continue reading", "follow us", "most read",
    )
    private val PREFIXES = listOf(
        "sign up for", "subscribe to", "read more:", "related:", "copyright", "image source,", "image caption,",
        "media caption,", "watch:", "listen:", "get our ",
    )
}
