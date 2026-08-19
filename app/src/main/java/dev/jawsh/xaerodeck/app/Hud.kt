package dev.jawsh.xaerodeck.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/** Tactical HUD palette — Meteor-adjacent purples on near-black. */
object Hud {
    val bg = Color(0xFF0B0B10)
    val panel = Color(0xFF100D17)
    val surface = Color(0xFF16121F)
    val surfaceHi = Color(0xFF1E1830)
    val border = Color(0xFF3A2F52)
    val accent = Color(0xFFB48CFF)
    val onAccent = Color(0xFF180A2B)
    val text = Color(0xFFEFE9FF)
    val sub = Color(0xFF8F86A8)
    val green = Color(0xFF7EE787)
    val orange = Color(0xFFFFB86C)
    val cyan = Color(0xFF8BE9FD)
    val pink = Color(0xFFFF79C6)
    val red = Color(0xFFFF8888)
    val yellow = Color(0xFFFFE066)
}

/** Sharp-cornered bordered surface, the HUD building block. */
@Composable
fun Modifier.hudPanel(border: Color = Hud.border, bg: Color = Hud.surface): Modifier =
    this.background(bg).border(1.dp, border).padding(1.dp)

/** Minecraft styled spans → Compose AnnotatedString. */
fun List<TextSpan>.toAnnotated(defaultColor: Color = Hud.text): AnnotatedString =
    buildAnnotatedString {
        for (s in this@toAnnotated) {
            pushStyle(SpanStyle(
                color = s.color?.let { Color(it) } ?: defaultColor,
                fontWeight = if (s.bold) FontWeight.Bold else null,
                fontStyle = if (s.italic) FontStyle.Italic else null,
                textDecoration = when {
                    s.underline && s.strike ->
                        TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
                    s.underline -> TextDecoration.Underline
                    s.strike -> TextDecoration.LineThrough
                    else -> null
                }
            ))
            append(s.text)
            pop()
        }
    }
