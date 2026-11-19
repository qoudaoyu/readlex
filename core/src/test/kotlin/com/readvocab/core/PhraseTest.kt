package com.readvocab.core

import com.readvocab.core.text.Lemmatizer
import com.readvocab.core.text.PhraseEntry
import com.readvocab.core.text.PhraseMatcher
import com.readvocab.core.text.Tokenizer
import org.junit.Assert.assertEquals
import org.junit.Test

class PhraseTest {
    private val lemma = mapOf("caught" to "catch", "catching" to "catch", "kept" to "keep", "took" to "take", "rates" to "rate")
    private val phrases = listOf(
        PhraseEntry("catch up with", listOf("catch", "up", "with"), PhraseEntry.GENERAL),
        PhraseEntry("catch up", listOf("catch", "up"), PhraseEntry.GENERAL),
        PhraseEntry("market capitalization", listOf("market", "capitalization"), PhraseEntry.GENERAL),
        PhraseEntry("keep one's word", listOf("keep", "#poss", "word"), PhraseEntry.GENERAL),
        PhraseEntry("take off", listOf("take", "off"), PhraseEntry.GENERAL),
        PhraseEntry("interest rate", listOf("interest", "rate"), PhraseEntry.GENERAL),
        PhraseEntry("accuse sb of sth", listOf("accuse", "#sb", "of", "#sth"), PhraseEntry.GENERAL),
    ).groupBy { it.key[0] }

    private fun found(text: String): List<String> {
        val tokens = Tokenizer.tokenize(text)
        val lemmas = tokens.map { t -> t.text.lowercase().let { lemma[it] ?: it } }
        return PhraseMatcher.match(text, tokens, lemmas) { phrases[it].orEmpty() }.map { text.substring(it.start, it.end) }
    }

    @Test fun findsPhrasesByLemma() {
        assertEquals(listOf("caught up with"), found("She finally caught up with the leaders."))
        assertEquals(listOf("catching up"), found("They are catching up fast."))
        assertEquals(listOf("market capitalization"), found("Its market capitalization doubled."))
        assertEquals(listOf("kept their word"), found("The ministers kept their word."))
        assertEquals(listOf("took it off"), found("He took it off at once."))
        assertEquals(listOf("interest rates"), found("Higher interest rates hurt."))
        assertEquals(listOf("accuse the bank of fraud"), found("They accuse the bank of fraud."))
    }

    @Test fun punctuationBreaksAPhrase() {
        assertEquals(emptyList<String>(), found("He tried to catch. Up with them came the rest."))
        assertEquals(emptyList<String>(), found("the market, capitalization aside"))
    }

    @Test fun commonFormKeepsItsOwnEntry() {
        val table = mapOf("according" to "accord", "mapped" to "map", "planting" to "plant")
        val ranks = mapOf("according" to 478, "accord" to 6075, "mapped" to 40149, "map" to 1542, "planting" to 7447, "plant" to 700)
        val l = Lemmatizer({ table[it] }, { it in ranks }, { ranks[it] })
        assertEquals("according", l.lemma("According"))
        assertEquals("map", l.lemma("mapped"))
        assertEquals("plant", l.lemma("planting"))
    }
}
