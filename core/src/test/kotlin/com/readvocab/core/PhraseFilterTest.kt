package com.readvocab.core

import com.readvocab.core.ai.ArticlePhrase
import com.readvocab.core.text.PhraseFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class PhraseFilterTest {
    private val dictionary = setOf("in general", "stepping stone", "catch up with", "market capitalization")
    private fun p(text: String, type: String = "", phrase: String = text) = ArticlePhrase(text, phrase, "意思", type)

    /** The picks from the BBC article in the report: keep the real expressions, drop the rest. */
    @Test fun keepsFixedExpressionsDropsPlainCombinations() {
        val picks = listOf(
            p("engage with", "phrasal_verb"),
            p("in general", "fixed"),
            p("having already run", "fixed"),
            p("creating opportunities", "fixed", "create opportunities"),
            p("neighbourhood wildlife corridors", "term"),
            p("bug hotels", "term"),
            p("act as stepping stones", "idiom", "act as a stepping stone"),
            p("stepping stones", "idiom", "stepping stone"),
            p("on their doorstep", "idiom", "on one's doorstep"),
            p("getting people involved", "phrasal_verb", "get people involved"),
            p("The National Lottery Heritage Fund", "term"),
            p("thriving habitats", "term"),
            p("caught up with", "phrasal_verb", "catch up with"),
            p("market capitalization", "term"),
        )
        val kept = PhraseFilter.filter(picks) { it in dictionary }.map { it.text }
        assertEquals(
            listOf("engage with", "in general", "stepping stones", "on their doorstep", "caught up with", "market capitalization"),
            kept,
        )
    }

    @Test fun trimsToTheDictionaryPhrase() {
        val t = PhraseFilter.trimToDictionary(p("act as stepping stones", "idiom", "act as a stepping stone")) { it in dictionary }
        assertEquals("stepping stones", t.text)
        assertEquals("stepping stone", t.phrase)
    }

    @Test fun dropsNamesAndAuxiliaries() {
        assertEquals(false, PhraseFilter.keep(p("Bank of England", "term")) { false })
        assertEquals(false, PhraseFilter.keep(p("has been building", "fixed")) { false })
        assertEquals(false, PhraseFilter.keep(p("raise money")) { false })
        assertEquals(true, PhraseFilter.keep(p("at stake", "idiom")) { false })
    }
}
