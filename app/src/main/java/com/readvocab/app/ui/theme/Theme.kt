package com.readvocab.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF2F5D50), onPrimary = Color.White,
    secondary = Color(0xFF7A5C1E), tertiary = Color(0xFF3D6A8A),
    background = Color(0xFFFBF8F1), surface = Color(0xFFFBF8F1),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF8FC9B5), secondary = Color(0xFFE2C27E), tertiary = Color(0xFFA6CBEA),
    background = Color(0xFF151816), surface = Color(0xFF151816),
)

/** Reader highlight colors, tuned to stay readable in both themes. */
object HighlightColors {
    fun learning(dark: Boolean) = if (dark) Color(0x66C9A227) else Color(0x66F2C14E)
    fun familiar(dark: Boolean) = if (dark) Color(0x3357A88A) else Color(0x3357A88A)
    fun newWord(dark: Boolean) = if (dark) Color(0xFF8FB8DE) else Color(0xFF2F6690)
}

@Composable
fun ReadVocabTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
