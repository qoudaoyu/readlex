package com.readvocab.core

import com.readvocab.core.text.ProperNouns
import com.readvocab.core.text.Tokenizer
import org.junit.Assert.assertEquals
import org.junit.Test

class ProperNounsTest {
    private fun names(paras: List<String>, title: Set<Int> = emptySet()): List<List<String>> =
        ProperNouns.find(paras) { it in title }.mapIndexed { i, starts ->
            Tokenizer.tokenize(paras[i]).filter { it.start in starts }.map { it.text }
        }

    @Test fun findsNamesButNotSentenceStarts() {
        val paras = listOf(
            "Why Ukraine Is Rebuilding The Kerch Bridge",
            "Ukraine has struck the bridge to Crimea again, officials in Kyiv said on Monday.",
            "Bridges matter. The attack was claimed by the SBU, which said: \"Repairs will take months.\"",
            "Officials said the bridge would reopen. Repairs began in Kerch, Ukraine said.",
        )
        val n = names(paras, title = setOf(0))
        assertEquals(listOf("Ukraine", "Kerch"), n[0]) // headline: only words also capitalised mid-sentence
        assertEquals(listOf("Ukraine", "Crimea", "Kyiv", "Monday"), n[1])
        assertEquals(listOf("SBU"), n[2])
        assertEquals(listOf("Kerch", "Ukraine"), n[3])
    }

    @Test fun wordAlsoSeenInLowerCaseIsNotAName() {
        val n = names(listOf("Rain fell on the Valley Road.", "The valley flooded."))
        assertEquals(listOf("Road"), n[0])
    }
}
