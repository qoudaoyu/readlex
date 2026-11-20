package com.readvocab.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readvocab.app.ui.MainActivity
import com.readvocab.app.ui.reader.ReaderSerif
import com.readvocab.app.ui.reader.nearestLine
import com.readvocab.app.ui.reader.tapOffset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TapTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun nearestLineByLetterMiddle() {
        assertEquals(0, nearestLine(listOf(10f, 40f, 70f), 24f))
        assertEquals(1, nearestLine(listOf(10f, 40f, 70f), 26f))
        assertEquals(2, nearestLine(listOf(10f, 40f, 70f), 500f))
    }

    /** A finger landing just under a word on line 2 opens that word, not the one below it. */
    @Test fun tapJustUnderAWordStaysOnItsLine() {
        val text = "Wildlife corridors connect gardens and parks across the city, and volunteers build bug hotels " +
            "so that insects can move freely between habitats that would otherwise be cut off from each other."
        var layout: TextLayoutResult? = null
        rule.activity.setContent {
            Text(
                text,
                style = TextStyle(fontFamily = ReaderSerif, fontSize = 18.sp, lineHeight = (18 * 1.65f).sp),
                modifier = Modifier.width(320.dp),
                onTextLayout = { layout = it },
            )
        }
        rule.waitForIdle()
        val l = layout!!
        check(l.lineCount >= 3)
        val em = with(l.layoutInput.density) { 18.sp.toPx() }
        val line = 1
        val start = l.getLineStart(line)
        val wordEnd = text.indexOf(' ', start).let { if (it < 0) l.getLineEnd(line) else it }
        val x = (l.getHorizontalPosition(start, true) + l.getHorizontalPosition(wordEnd, true)) / 2
        // Just below the letters: where the finger's centre lands when it covers the word.
        val y = l.getLineBaseline(line) + em * 0.5f
        // The platform's own mapping puts this point on the next line: the bug that was reported.
        assertEquals(line + 1, l.getLineForOffset(l.getOffsetForPosition(Offset(x, y))))
        val off = tapOffset(l, Offset(x, y))
        assertEquals(line, l.getLineForOffset(off))
        assert(off in start..wordEnd) { "tap at $off, word at $start..$wordEnd" }
        // And a tap on the word itself still lands on it.
        assertEquals(line, l.getLineForOffset(tapOffset(l, Offset(x, l.getLineBaseline(line) - em * 0.3f))))
        // A tap on the next line's letters goes to the next line.
        assertEquals(line + 1, l.getLineForOffset(tapOffset(l, Offset(x, l.getLineBaseline(line + 1) - em * 0.4f))))
    }
}
