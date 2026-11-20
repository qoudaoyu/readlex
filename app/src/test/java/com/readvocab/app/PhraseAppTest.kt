package com.readvocab.app

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.readvocab.app.data.BookEntity
import com.readvocab.app.ui.reader.PhraseSpan
import com.readvocab.app.ui.reader.ReaderViewModel
import com.readvocab.core.text.PhraseEntry
import com.readvocab.core.vocab.WordStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PhraseAppTest {
    private val app = ApplicationProvider.getApplicationContext<ReadVocabApp>()
    private val c get() = app.container

    private fun waitFor(what: String, cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!cond()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > until) throw AssertionError("timed out: $what")
            Thread.sleep(20)
        }
    }

    private val text = "# Markets rally\n\n" +
        "The company's market capitalization rose sharply on Monday, and smaller rivals caught up with it by the evening.\n\n" +
        "Analysts said the firm would have to step up its efforts. Officials kept their word."

    @Test fun findsAndShowsPhrases() {
        val id = runBlocking {
            c.dictionary.ensureLoaded()
            c.settings.update { it.copy(knownBoundary = 3000, knownCurve = "", aiPhrases = false) }
            c.books.importText("Markets rally", text, "Example News", BookEntity.KIND_ARTICLE, "https://example.com/markets")
        }
        // The AI's picks for this article, as the reader caches them after asking once.
        c.books.cacheFile(id, ReaderViewModel.AI_PHRASES_FILE).writeText("""[{"text":"step up","phrase":"step up","meaning":"加大，加强"}]""")
        val vm = ReaderViewModel(c, id)
        waitFor("chapter") { !vm.state.value.loading && vm.state.value.paragraphs.isNotEmpty() }
        val paras = vm.state.value.paragraphs
        val p1 = paras[1]
        val p2 = paras[2]
        assertEquals(listOf("market capitalization", "catch up with"), p1.phrases.map { it.phrase })
        assertTrue("keep one's word" in p2.phrases.map { it.phrase })
        val ai = p2.phrases.first { it.kind == PhraseSpan.AI }
        assertEquals("step up", p2.text.substring(ai.start, ai.end))
        assertEquals("加大，加强", ai.meaning)

        val statuses = vm.statuses.value
        val s = c.settings.current
        // Shown: the term holding a rare word, and the AI's pick; not a general phrase of known words.
        assertTrue(vm.phraseShown(p1, p1.phrases[0], statuses, s))
        assertTrue(vm.phraseShown(p2, ai, statuses, s))
        assertTrue(!vm.phraseShown(p1, p1.phrases[1], statuses, s))

        // Tapping any word of a phrase opens the phrase.
        vm.onTap(1, p1.text.indexOf("capitalization") + 2)
        waitFor("lookup") { vm.lookup.value != null }
        assertEquals("market capitalization", vm.lookup.value!!.lemma)
        assertTrue(vm.lookup.value!!.entry!!.translation.contains("市场资本"))
        vm.dismissLookup()
        vm.onTap(1, p1.text.indexOf("with it") + 1)
        waitFor("lookup") { vm.lookup.value != null }
        assertEquals("catch up with", vm.lookup.value!!.lemma)
        assertEquals("caught up with", vm.lookup.value!!.surface)
        // …and the word on its own is one tap away.
        vm.lookupWordInstead()
        waitFor("word") { vm.lookup.value?.lemma == "with" }
        vm.dismissLookup()

        // The AI's phrase carries its meaning here; added to the word list, it comes back in review.
        vm.onTap(2, p2.text.indexOf("step up") + 1)
        waitFor("lookup") { vm.lookup.value?.lemma == "step up" }
        assertTrue(vm.lookup.value!!.entry!!.translation.startsWith("本文中：加大"))
        vm.addToWordList()
        waitFor("added") { runBlocking { c.words.get("step up") }?.status == WordStatus.LEARNING }
        assertNotNull(runBlocking { c.dictionary.lookup("step up") })
        assertEquals("加大，加强", runBlocking { c.words.encounters("step up") }.first().aiMeaning)
        assertEquals("step up", runBlocking { c.words.encounters("step up") }.first().surface)
    }

    @Test fun termsOfKnownWordsAreNotTapTargets() = runBlocking {
        c.dictionary.ensureLoaded()
        // A finance term exists for "interest rate"; general phrases still answer a tap even when all words are known.
        assertTrue(c.dictionary.phrasesStartingWith("interest").any { it.phrase == "interest rate" })
        assertTrue(c.dictionary.phrasesStartingWith("catch").any { it.phrase == "catch up with" && it.kind == PhraseEntry.GENERAL })
    }
}
