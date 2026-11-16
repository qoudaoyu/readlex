package com.readvocab.core.importer

/** Lenient XHTML -> plain text conversion (EPUB content is often not strictly valid XML). */
object HtmlText {
    private val DROP = Regex("(?is)<(head|script|style|svg|nav)\\b.*?</\\1\\s*>")
    private val COMMENT = Regex("(?s)<!--.*?-->")
    private val BLOCK = Regex("(?i)</?(p|div|br|h[1-6]|li|tr|blockquote|section|article|dd|dt|pre|hr)\\b[^>]*>")
    private val TAG = Regex("(?s)<[^>]+>")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
    private val TITLE = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val HEADING = Regex("(?is)<h([1-3])[^>]*>(.*?)</h\\1>")

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "mdash" to "\u2014", "ndash" to "\u2013", "hellip" to "\u2026", "lsquo" to "\u2018",
        "rsquo" to "\u2019", "ldquo" to "\u201C", "rdquo" to "\u201D", "copy" to "\u00A9",
        "shy" to "", "eacute" to "é", "egrave" to "è", "agrave" to "à", "ccedil" to "ç",
        "uuml" to "ü", "ouml" to "ö", "auml" to "ä", "iuml" to "ï", "ecirc" to "ê",
    )

    fun decodeEntities(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> NAMED[e] ?: m.value
        }
    }

    fun toText(html: String): String {
        var s = COMMENT.replace(html, "")
        s = DROP.replace(s, "")
        s = BLOCK.replace(s, "\n")
        s = TAG.replace(s, "")
        s = decodeEntities(s)
        return s.lines().joinToString("\n") { it.replace(Regex("[ \t\u00A0]+"), " ").trim() }
            .replace(Regex("\n{3,}"), "\n\n").trim()
    }

    fun title(html: String): String? {
        HEADING.find(html)?.let { val t = clean(it.groupValues[2]); if (t.isNotBlank()) return t }
        TITLE.find(html)?.let { val t = clean(it.groupValues[1]); if (t.isNotBlank()) return t }
        return null
    }

    private fun clean(s: String) = decodeEntities(TAG.replace(s, " ")).replace(Regex("\\s+"), " ").trim()
}
