package com.readvocab.core.importer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

object TxtImporter {
    private val HEADING = Regex(
        "^(chapter|part|book)\\s+([0-9]+|[ivxlcdm]+|one|two|three|four|five|six|seven|eight|nine|ten|[a-z-]+)\\b.*$|^第[0-9一二三四五六七八九十百千]+[章节回].*$",
        RegexOption.IGNORE_CASE,
    )
    /** Chapters without headings are cut into chunks of roughly this many characters. */
    private const val CHUNK_CHARS = 12_000

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return String(bytes, Charsets.UTF_16LE).drop(1)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return String(bytes, Charsets.UTF_16BE).drop(1)
        strictDecode(bytes, Charsets.UTF_8)?.let { return it }
        runCatching { Charset.forName("GB18030") }.getOrNull()?.let { cs -> strictDecode(bytes, cs)?.let { return it } }
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun strictDecode(bytes: ByteArray, cs: Charset): String? = try {
        cs.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (e: CharacterCodingException) { null }

    fun parse(bytes: ByteArray, fileName: String): ParsedBook {
        val text = decode(bytes).replace("\r\n", "\n").replace('\r', '\n')
        if (text.isBlank()) throw ImportException("文件是空的")
        val title = fileName.substringBeforeLast('.').ifBlank { "未命名" }
        return ParsedBook(title, "", splitChapters(text))
    }

    fun splitChapters(text: String): List<ParsedChapter> {
        val lines = text.lines()
        val headings = lines.indices.filter { i ->
            val l = lines[i].trim()
            l.length in 3..80 && HEADING.matches(l)
        }
        val chapters = mutableListOf<ParsedChapter>()
        if (headings.size >= 2) {
            val preface = lines.subList(0, headings.first()).joinToString("\n").trim()
            if (preface.length > 200) chapters += ParsedChapter("开头", preface)
            headings.forEachIndexed { idx, start ->
                val end = if (idx + 1 < headings.size) headings[idx + 1] else lines.size
                val body = lines.subList(start + 1, end).joinToString("\n").trim()
                if (body.isNotBlank()) chapters += ParsedChapter(lines[start].trim(), body)
            }
        }
        if (chapters.isEmpty()) {
            // No headings: chunk by paragraphs.
            val sb = StringBuilder()
            var n = 1
            for (para in text.split(Regex("\n\\s*\n"))) {
                if (sb.length + para.length > CHUNK_CHARS && sb.isNotEmpty()) {
                    chapters += ParsedChapter("第 ${n++} 部分", sb.toString().trim()); sb.clear()
                }
                sb.append(para).append("\n\n")
            }
            if (sb.isNotBlank()) chapters += ParsedChapter(if (n == 1) "全文" else "第 $n 部分", sb.toString().trim())
        }
        return chapters
    }
}
