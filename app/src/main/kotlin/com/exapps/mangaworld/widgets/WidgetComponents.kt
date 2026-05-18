package com.exapps.mangaworld.widgets

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.background
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.material3.ButtonDefaults
import androidx.glance.material3.FilledButton
import androidx.glance.material3.GlanceTheme
import com.exapps.mangaworld.R

@Composable
internal fun WidgetCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.surface)
            .cornerRadius(20.dp)
            .padding(14.dp)
    ) {
        Text(
            text = title,
            style = TextStyle(
                color = GlanceTheme.colors.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(GlanceModifier.height(10.dp))
        content()
    }
}

@Composable
internal fun WidgetCover(provider: ImageProvider, description: String?) {
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(112.dp)
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(16.dp),
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
    FilledButton(
        text = label,
        onClick = actionStartActivity(intent),
        modifier = GlanceModifier.fillMaxWidth(),
        colors = ButtonDefaults.buttonColors()
    )
}

@Composable
internal fun WidgetListItem(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    intent: Intent
) {
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .clickable(actionStartActivity(intent))
            .background(GlanceTheme.colors.surfaceVariant)
            .cornerRadius(14.dp)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = title,
                maxLines = 1,
                style = TextStyle(
                    color = GlanceTheme.colors.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            )
            if (!subtitle.isNullOrBlank()) {
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
        if (!trailing.isNullOrBlank()) {
            Spacer(GlanceModifier.width(8.dp))
            Text(
                text = trailing,
                style = TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            )
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
        Image(
            provider = ImageProvider(R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = GlanceModifier.size(42.dp)
        )
        Spacer(GlanceModifier.height(8.dp))
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
