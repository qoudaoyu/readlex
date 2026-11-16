package com.readvocab.core.importer

import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.zip.ZipInputStream

object EpubImporter {
    private val ROOTFILE = Regex("(?i)<rootfile[^>]*full-path\\s*=\\s*\"([^\"]+)\"")
    private val ITEM = Regex("(?is)<(?:\\w+:)?item\\b([^>]*)/?>")
    private val ITEMREF = Regex("(?is)<(?:\\w+:)?itemref\\b([^>]*)/?>")
    private val TITLE = Regex("(?is)<dc:title[^>]*>(.*?)</dc:title>")
    private val CREATOR = Regex("(?is)<dc:creator[^>]*>(.*?)</dc:creator>")

    private fun attr(attrs: String, name: String): String? =
        Regex("(?i)\\b$name\\s*=\\s*[\"']([^\"']*)[\"']").find(attrs)?.groupValues?.get(1)

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) out[e.name] = zip.readBytes()
            }
        }
        return out
    }

    private fun resolve(baseDir: String, href: String): String {
        val decoded = runCatching { URLDecoder.decode(href.substringBefore('#'), "UTF-8") }.getOrDefault(href.substringBefore('#'))
        val parts = ArrayList<String>()
        (if (baseDir.isEmpty()) decoded else "$baseDir/$decoded").split('/').forEach {
            when (it) { "", "." -> Unit; ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1); else -> parts += it }
        }
        return parts.joinToString("/")
    }

    fun parse(bytes: ByteArray, fileName: String): ParsedBook {
        val files = try { unzip(bytes) } catch (e: Exception) { throw ImportException("不是有效的 EPUB 文件", e) }
        val container = files["META-INF/container.xml"]?.toString(Charsets.UTF_8)
            ?: throw ImportException("EPUB 缺少 container.xml")
        val opfPath = ROOTFILE.find(container)?.groupValues?.get(1) ?: throw ImportException("EPUB 缺少 OPF 文件")
        val opf = files[opfPath]?.toString(Charsets.UTF_8) ?: throw ImportException("找不到 $opfPath")
        val baseDir = opfPath.substringBeforeLast('/', "")

        val manifest = HashMap<String, Pair<String, String>>() // id -> (href, media-type)
        ITEM.findAll(opf).forEach { m ->
            val a = m.groupValues[1]
            val id = attr(a, "id") ?: return@forEach
            manifest[id] = (attr(a, "href") ?: return@forEach) to (attr(a, "media-type") ?: "")
        }
        val spine = ITEMREF.findAll(opf).mapNotNull { attr(it.groupValues[1], "idref") }.toList()

        val title = TITLE.find(opf)?.groupValues?.get(1)?.let { HtmlText.decodeEntities(it).trim() }
            ?.takeIf { it.isNotBlank() } ?: fileName.substringBeforeLast('.')
        val author = CREATOR.find(opf)?.groupValues?.get(1)?.let { HtmlText.decodeEntities(it).trim() }.orEmpty()

        val chapters = mutableListOf<ParsedChapter>()
        for (idref in spine) {
            val (href, type) = manifest[idref] ?: continue
            if (!type.contains("html", ignoreCase = true) && !href.endsWith("html", true) && !href.endsWith("htm", true)) continue
            val html = files[resolve(baseDir, href)]?.toString(Charsets.UTF_8) ?: continue
            val text = HtmlText.toText(html)
            if (text.length < 40) continue // covers, blank pages
            val chTitle = HtmlText.title(html)?.take(80) ?: "第 ${chapters.size + 1} 章"
            chapters += ParsedChapter(chTitle, text)
        }
        if (chapters.isEmpty()) throw ImportException("EPUB 中没有找到正文")
        return ParsedBook(title, author, chapters)
    }
}
