package com.exapps.mangaworld.presentation.theme

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

/**
 * Content-aware text direction.
 *
 * The app chrome is Arabic-first (RTL layout), but dynamic content such as manga
 * titles, descriptions and user comments can be English. A global
 * [androidx.compose.ui.unit.LayoutDirection.Rtl] makes `TextAlign.Start` resolve
 * to the right, so English titles look RTL-aligned. [TextDirection.Content]
 * alone fixes glyph order but NOT alignment.
 *
 * These helpers align each string by its own first strong directional character:
 * Arabic/Hebrew → right (RTL), anything else → left (LTR), using absolute
 * alignment so the app layout direction cannot override it.
 */

fun String.isRtlContent(): Boolean {
    for (ch in this) {
        // Hebrew + Arabic blocks (incl. Presentation Forms and Supplement).
        if (ch in '\u0590'..'\u08FF' || ch in '\uFB00'..'\uFDFF' || ch in '\uFE70'..'\uFEFF') return true
        // Latin / digits are strong LTR — decide immediately on the first
        // strong character (numbers and neutrals before it are skipped).
        if (ch in 'A'..'Z' || ch in 'a'..'z') return false
    }
    return true
}

fun contentTextAlign(text: String): TextAlign =
    if (text.isRtlContent()) TextAlign.Right else TextAlign.Left

fun contentTextDirection(text: String): TextDirection =
    if (text.isRtlContent()) TextDirection.Rtl else TextDirection.Ltr

/**
 * Drop-in Text for dynamic (manga/user) content. Static UI strings from
 * resources keep the default directional alignment; use this for titles,
 * descriptions, author names, chapter titles and comments.
 */
@Composable
fun LocalizedText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    textDecoration: TextDecoration? = null,
    fontSize: TextUnit = TextUnit.Unspecified,
    onTextLayout: (TextLayoutResult) -> Unit = {}
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = style.copy(textDirection = contentTextDirection(text)),
        textAlign = contentTextAlign(text),
        maxLines = maxLines,
        overflow = overflow,
        fontWeight = fontWeight,
        fontStyle = fontStyle,
        textDecoration = textDecoration,
        fontSize = fontSize,
        onTextLayout = onTextLayout
    )
}

/** Material defaults with content-aware direction for dynamic content. */
@Composable
fun localizedTitleStyle(): TextStyle =
    MaterialTheme.typography.titleLarge.copy(textDirection = TextDirection.Content)

@Composable
fun localizedBodyStyle(): TextStyle =
    MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content)
