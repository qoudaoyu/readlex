package com.readvocab.core.text

/**
 * Light paragraph markup in stored book text, so imported articles keep their shape:
 * "# " headline, "## " subheading, "> " quotation. Everything else is body text
 * (list items are stored with a leading bullet and read as body text).
 */
object ParaMarkup {
    enum class Kind { BODY, TITLE, HEADING, QUOTE }

    fun parse(paragraph: String): Pair<Kind, String> = when {
        paragraph.startsWith("## ") -> Kind.HEADING to paragraph.substring(3).trim()
        paragraph.startsWith("# ") -> Kind.TITLE to paragraph.substring(2).trim()
        paragraph.startsWith("> ") -> Kind.QUOTE to paragraph.substring(2).trim()
        else -> Kind.BODY to paragraph
    }

    /** Text without markup, for word counts and statistics. */
    fun strip(text: String): String = text.lineSequence().joinToString("\n") { parse(it.trimStart()).second }
}
