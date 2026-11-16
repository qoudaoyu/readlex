package com.readvocab.app

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.core.app.ApplicationProvider
import com.readvocab.app.data.PassedWord
import com.readvocab.app.ui.MainActivity
import com.readvocab.core.srs.Rating
import com.readvocab.core.vocab.VocabEstimator
import com.readvocab.core.vocab.WordStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@org.robolectric.annotation.Config(qualifiers = "w411dp-h891dp-xxhdpi")
class AppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val container get() = ApplicationProvider.getApplicationContext<ReadVocabApp>().container

    private fun shot(name: String) {
        val dir = File(System.getProperty("user.dir"), "build/screenshots").apply { mkdirs() }
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(android.graphics.Canvas(bitmap)) }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun dictionaryAndWordModel() = runBlocking {
        val dict = container.dictionary
        dict.ensureLoaded()
        assertEquals("run", dict.lemma("ran"))
        assertEquals("study", dict.lemma("Studies"))
        assertEquals("child", dict.lemma("children"))
        val entry = dict.lookup("reluctant")
        assertNotNull(entry)
        assertTrue(entry!!.translation.isNotBlank())
        assertNull(dict.rank("xqzzy"))
        val sample = dict.sample(VocabEstimator.BANDS[7], 6)
        assertEquals(6, sample.size)

        val words = container.words
        assertNull(words.onLookup("reluctant")) // looking up alone does not add the word
        assertNull(words.get("reluctant"))
        val w = words.addToLearning("reluctant", "reluctant", "He was reluctant to go.", null, "Test", "不情愿的")
        assertEquals(WordStatus.LEARNING, w.status)
        assertEquals("不情愿的", words.encounters("reluctant").single().aiMeaning)
        assertEquals(1, words.encounters("reluctant").size)

        // Not due yet: reading past it does nothing.
        var r = words.commitReading(listOf(PassedWord("reluctant", "reluctant", "Still reluctant.", false)), null, "Test", 3)
        assertEquals(0, r.reviewed)
        // A new word read past three times becomes known.
        repeat(3) { r = words.commitReading(listOf(PassedWord("plateau", "plateau", "A plateau.", true)), null, "Test", 3) }
        assertEquals(1, r.autoKnown)
        assertEquals(WordStatus.KNOWN, words.get("plateau")!!.status)

        words.rate("reluctant", Rating.Good)
        assertTrue(words.get("reluctant")!!.reps >= 2)

        // Review story: the generator picks words from the list, saves the article as a book.
        for (w in listOf("plateau", "evaporate", "blunt")) words.setStatus(w, WordStatus.LEARNING)
        var prompt = ""
        val fake = object : com.readvocab.core.ai.AiProvider {
            override fun complete(system: String, user: String, maxTokens: Int): String {
                prompt = user
                return """{"title": "The Hill", "body": "She was reluctant to climb.\n\nThe water evaporated on the plateau."}"""
            }
        }
        val gen = com.readvocab.app.data.StoryGenerator(words, container.books, dict, container.settings) { com.readvocab.core.ai.AiTutor(fake) }
        val result = gen.generate("科普")
        assertEquals(setOf("reluctant", "plateau", "evaporate", "blunt"), result.words.toSet())
        assertEquals(listOf("blunt"), result.missing)
        assertTrue(prompt.contains("plateau"))
        val book = container.books.get(result.bookId)!!
        assertEquals("复习：The Hill", book.title)
        assertTrue(container.books.chapterText(result.bookId, 0).contains("evaporated on the plateau"))
    }

    @Test fun libraryReaderAndScreens() {
        // Sample book is imported on first launch.
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("The Quiet Power", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        shot("1-library")
        compose.onAllNodes(hasText("The Quiet Power", substring = true))[0].performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("plateau", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        shot("2-reader")

        // Tap the word "plateau" inside its paragraph.
        val node = compose.onNode(hasText("plateau", substring = true))
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.first()
        val offset = layout.layoutInput.text.text.indexOf("plateau") + 2
        val box = layout.getBoundingBox(offset)
        node.performTouchInput { click(box.center) }
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("原句")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        shot("2b-wordsheet")
        assertNull(runBlocking { container.words.get("plateau") })
        compose.onNode(hasText("加入生词表")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("已在生词表", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(WordStatus.LEARNING, runBlocking { container.words.get("plateau") }?.status)
        androidx.test.espresso.Espresso.pressBack() // closes the word sheet (its own window)
        compose.waitForIdle()

        // Long-press a sentence in paragraph 1 and drag into paragraph 2: whole sentences get selected.
        val list = compose.onNode(hasTestTag("reader-list"))
        val listBounds = list.fetchSemanticsNode().boundsInRoot
        fun pointIn(text: String, needle: String): Offset {
            val n = compose.onNode(hasText(text, substring = true))
            val ls = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            n.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(ls) }
            val box = ls.first().getBoundingBox(ls.first().layoutInput.text.text.indexOf(needle) + 1)
            val b = n.fetchSemanticsNode().boundsInRoot
            return Offset(b.left - listBounds.left, b.top - listBounds.top) + box.center
        }
        val from = pointIn("frustrating plateau", "dense")
        val to = pointIn("Researchers who study", "blunt")
        list.performTouchInput {
            down(from); advanceEventTime(1000); moveTo(to); advanceEventTime(50); up()
        }
        compose.waitForIdle()
        shot("2c-selection")
        // "They have memorized ... dense ..." through the end of paragraph 1 (3 sentences) + 1 sentence of paragraph 2
        compose.onNode(hasText("已选 4 句")).assertExists()
        assertNull(runBlocking { container.words.get("dense") }) // the long press did not open a lookup
        compose.onNode(hasText("翻译并解析")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("API Key", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        androidx.test.espresso.Espresso.pressBack() // close the sentence sheet
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() } // leave the reader
        compose.waitForIdle()

        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNode(hasText("开始测试")).performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("第 1 /", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        shot("3-vocabtest")
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNode(hasText("生词本")).performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("plateau")).fetchSemanticsNodes().isNotEmpty() }
        shot("4-wordbook")
        compose.onNode(hasText("复习")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("生成复习短文")).fetchSemanticsNodes().isNotEmpty() }
        shot("6-review")
        compose.onNode(hasText("设置")).performClick()
        compose.waitForIdle()
        shot("5-settings")
    }
}
