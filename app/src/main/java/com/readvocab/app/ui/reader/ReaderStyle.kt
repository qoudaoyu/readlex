package com.readvocab.app.ui.reader

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.readvocab.app.R
import com.readvocab.core.vocab.Coverage

/** Magazine-style reader: Source Serif 4 for reading, Inter for labels (both SIL OFL, bundled). */
val ReaderSerif = FontFamily(
    Font(R.font.reader_serif_regular, FontWeight.Normal),
    Font(R.font.reader_serif_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.reader_serif_bold, FontWeight.Bold),
)

val ReaderSans = FontFamily(
    Font(R.font.reader_sans_medium, FontWeight.Medium),
    Font(R.font.reader_sans_semibold, FontWeight.SemiBold),
)

data class ReaderColors(
    val dark: Boolean,
    val background: Color,
    val text: Color,
    val heading: Color,
    val muted: Color,
    val accent: Color,
    val rule: Color,
    val strongRule: Color,
)

val ReaderLight = ReaderColors(
    dark = false,
    background = Color(0xFFFFFFFF),
    text = Color(0xFF1C1C1C),
    heading = Color(0xFF111111),
    muted = Color(0xFF595959),
    accent = Color(0xFFB4372B),
    rule = Color(0xFFE6E6E6),
    strongRule = Color(0xFF1C1C1C),
)

val ReaderDark = ReaderColors(
    dark = true,
    background = Color(0xFF141517),
    text = Color(0xFFD8D4CC),
    heading = Color(0xFFEEEAE2),
    muted = Color(0xFFA9A49B),
    accent = Color(0xFFE8A598),
    rule = Color(0xFF2C2E31),
    strongRule = Color(0xFFD8D4CC),
)

enum class ReaderTheme(val label: String) { SYSTEM("跟随系统"), LIGHT("日间"), DARK("夜间") }

fun readerTheme(setting: String): ReaderTheme = runCatching { ReaderTheme.valueOf(setting) }.getOrDefault(ReaderTheme.SYSTEM)

/** Background and text of the "认识 96% · 合适" chip: green when the level suits, amber or red when it is hard. */
fun coverageChipColors(level: Coverage.Level, dark: Boolean): Pair<Color, Color> = when (level) {
    Coverage.Level.EASY, Coverage.Level.JUST_RIGHT -> if (dark) Color(0xFF1F3329) to Color(0xFF9FD6B8) else Color(0xFFE8F2EC) to Color(0xFF1F5C3E)
    Coverage.Level.CHALLENGING -> if (dark) Color(0xFF3A2E14) to Color(0xFFF2C879) else Color(0xFFFFF1D6) to Color(0xFF6E4400)
    Coverage.Level.HARD -> if (dark) Color(0xFF3A1F1C) to Color(0xFFF0A79C) else Color(0xFFFBE4E1) to Color(0xFF8C2A20)
}

/** Learners read more slowly than natives; 150 words a minute is a fair estimate at a suitable level. */
fun readingMinutes(words: Int): Int = (words / 150).coerceAtLeast(1)
