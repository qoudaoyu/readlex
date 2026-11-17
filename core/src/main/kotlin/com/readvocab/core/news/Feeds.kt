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

/** Turns the HTML that Readability extracts from an article page into reader paragraphs. */
object ArticleText {
    private val DROP = Regex("(?is)<(figure|figcaption|button|aside|form|noscript|iframe)\\b.*?</\\1\\s*>")

    fun fromHtml(html: String): String = HtmlText.toText(DROP.replace(html, ""))
        .lines()
        // Short promo lines only; a real sentence that happens to say "subscribe to" stays.
        .filter { line -> line.isNotBlank() && !(line.length < 120 && BOILERPLATE.any { line.startsWith(it, ignoreCase = true) }) }
        .joinToString("\n\n")

    private val BOILERPLATE = listOf("Sign up for", "Subscribe to", "Read more:", "Related Topics", "Copyright ©")
}
