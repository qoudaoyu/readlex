package com.readvocab.app

import android.graphics.Bitmap
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
        val w = words.onLookup("reluctant", "reluctant", "He was reluctant to go.", null, "Test")
        assertEquals(WordStatus.LEARNING, w.status)
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
        val saved = runBlocking { container.words.get("plateau") }
        assertEquals(WordStatus.LEARNING, saved?.status)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() } // close sheet
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
        compose.onNode(hasText("设置")).performClick()
        compose.waitForIdle()
        shot("5-settings")
    }
}
