package com.exapps.mangaworld.widgets

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.ButtonColors
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.background
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.border
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.defaultWeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.exapps.mangaworld.R

/**
 * Button colors — matches the primary "متابعة القراءة" / زر أساسي
 * pill from the preview mock.
 */
private val mangaButtonColors
    @Composable get() = ButtonDefaults.buttonColors(
        backgroundColor = GlanceTheme.colors.primary,
        contentColor = GlanceTheme.colors.onPrimary
    )

@Composable
internal fun WidgetCard(
    title: String,
    showTitle: Boolean = true,
    transparentBg: Boolean = false,
    content: @Composable () -> Unit
) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .then(
                if (transparentBg) GlanceModifier.background(ColorProvider(day = Color.Transparent, night = Color.Transparent))
                else GlanceModifier.background(GlanceTheme.colors.surface)
            )
            .cornerRadius(22.dp)
            .border(width = 1.dp, color = GlanceTheme.colors.outline)
            .padding(14.dp)
    ) {
        if (showTitle) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Small red accent tick, echoing the red section markers in the mock.
                Box(
                    modifier = GlanceModifier
                        .size(4.dp)
                        .background(GlanceTheme.colors.primary)
                        .cornerRadius(2.dp)
                ) {}
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = title,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
            Spacer(GlanceModifier.height(10.dp))
        }
        content()
    }
}

@Composable
internal fun WidgetCover(provider: ImageProvider, description: String?, showCover: Boolean = true) {
    if (!showCover) return
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(112.dp)
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(16.dp)
            .border(width = 1.dp, color = GlanceTheme.colors.outline),
        contentAlignment = Alignment.Center
    ) {
        Image(
            provider = provider,
            contentDescription = description,
            modifier = GlanceModifier.fillMaxSize()
        )
    }
}

@Composable
internal fun WidgetPrimaryButton(label: String, intent: Intent) {
    Button(
        text = label,
        onClick = actionStartActivity(intent),
        modifier = GlanceModifier.fillMaxWidth().cornerRadius(14.dp),
        colors = mangaButtonColors
    )
}

@Composable
internal fun WidgetListItem(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    showTitle: Boolean = true,
    showSubtitle: Boolean = true,
    showBadge: Boolean = true,
    leadingDotActive: Boolean? = null,
    intent: Intent
) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .clickable(actionStartActivity(intent))
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(14.dp)
            .border(width = 1.dp, color = GlanceTheme.colors.outline)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingDotActive != null) {
            WidgetStatusDot(active = leadingDotActive)
            Spacer(GlanceModifier.width(8.dp))
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            if (showTitle) {
                Text(
                    text = title,
                    maxLines = 1,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
            if (showSubtitle && !subtitle.isNullOrBlank()) {
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    text = subtitle,
                    maxLines = 2,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                )
            }
        }
        if (showBadge && !trailing.isNullOrBlank()) {
            Spacer(GlanceModifier.width(8.dp))
            // Pill badge on a faint primary-tinted background, matching the small
            // red/gold status chips ("جديد" / "مكتمل") in the preview mock.
            Box(
                modifier = GlanceModifier
                    .background(GlanceTheme.colors.primaryContainer)
                    .cornerRadius(10.dp)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = trailing,
                    style = TextStyle(
                        color = GlanceTheme.colors.onPrimaryContainer,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
}

@Composable
internal fun WidgetEmptyState(title: String, subtitle: String, intent: Intent? = null, actionLabel: String? = null) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally
    ) {
        Box(
            modifier = GlanceModifier
                .size(56.dp)
                .background(GlanceTheme.colors.surfaceVariant)
                .cornerRadius(28.dp)
                .border(width = 1.dp, color = GlanceTheme.colors.outline),
            contentAlignment = Alignment.Center
        ) {
            Image(
                provider = ImageProvider(R.mipmap.ic_launcher),
                contentDescription = null,
                modifier = GlanceModifier.size(30.dp)
            )
        }
        Spacer(GlanceModifier.height(10.dp))
        Text(
            text = title,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        )
        Spacer(GlanceModifier.height(4.dp))
        Text(
            text = subtitle,
            style = TextStyle(
                color = GlanceTheme.colors.onSurfaceVariant,
                fontSize = 12.sp
            )
        )
        if (intent != null && !actionLabel.isNullOrBlank()) {
            Spacer(GlanceModifier.height(10.dp))
            WidgetPrimaryButton(label = actionLabel, intent = intent)
        }
    }
}

@Composable
internal fun WidgetSectionHeader(title: String) {
    Text(
        text = title,
        style = TextStyle(
            color = GlanceTheme.colors.onSurfaceVariant,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        ),
        modifier = GlanceModifier.padding(bottom = 4.dp)
    )
}

/**
 * Small status dot badge (e.g. unread indicator on latest-updates rows),
 * matching the tiny red/gray dots seen next to "أحدث التحديثات" entries.
 */
@Composable
internal fun WidgetStatusDot(active: Boolean) {
    Box(
        modifier = GlanceModifier
            .size(7.dp)
            .background(if (active) GlanceTheme.colors.primary else GlanceTheme.colors.outline)
            .cornerRadius(4.dp)
    ) {}
}
