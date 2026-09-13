package com.exapps.mangaworld.presentation.community

import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.exapps.mangaworld.presentation.theme.MangaColors

/**
 * @username mention support for community text.
 *
 * The server extracts mentions with `@([A-Za-z0-9_]{3,30})`
 * (FirebaseCommunityRepository.extractMentions); this parser mirrors it so
 * exactly the usernames the backend recognises become tappable profile links.
 * Tapping resolves `usernames/{name} → uid` (CommunityViewModel.resolveMention
 * and siblings) and opens the public profile. Guests get the sign-in prompt
 * (same gate as every other community write affordance). Without a click
 * handler the text renders exactly like [LocalizedText] with no dead affordance.
 */
internal val MENTION_REGEX = Regex("@([A-Za-z0-9_]{3,30})")

/** Usernames mentioned in [text], in order of appearance, deduplicated. Pure — unit-tested. */
internal fun extractMentionUsernames(text: String): List<String> =
    MENTION_REGEX.findAll(text).map { it.groupValues[1] }.distinct().toList()

private const val MENTION_TAG = "mention"

@Composable
internal fun MentionText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
    fontWeight: FontWeight? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    onMentionClick: ((String) -> Unit)? = null
) {
    if (onMentionClick == null || !MENTION_REGEX.containsMatchIn(text)) {
        Text(
            text = text,
            modifier = modifier,
            color = color,
            style = style,
            fontWeight = fontWeight,
            maxLines = maxLines,
            overflow = overflow
        )
        return
    }
    val annotated: AnnotatedString = remember(text) {
        buildAnnotatedString {
            var cursor = 0
            for (match in MENTION_REGEX.findAll(text)) {
                if (match.range.first > cursor) {
                    append(text.substring(cursor, match.range.first))
                }
                val username = match.groupValues[1]
                pushStringAnnotation(tag = MENTION_TAG, annotation = username)
                withStyle(SpanStyle(color = MangaColors.Cyan, fontWeight = FontWeight.SemiBold)) {
                    append(match.value)
                }
                pop()
                cursor = match.range.last + 1
            }
            if (cursor < text.length) append(text.substring(cursor))
        }
    }
    ClickableText(
        text = annotated,
        modifier = modifier,
        style = if (fontWeight != null) {
            style.merge(color = color, fontWeight = fontWeight)
        } else {
            style.merge(color = color)
        },
        maxLines = maxLines,
        overflow = overflow,
        onClick = { offset ->
            annotated.getStringAnnotations(tag = MENTION_TAG, start = offset, end = offset)
                .firstOrNull()?.let { onMentionClick(it.item) }
        }
    )
}
