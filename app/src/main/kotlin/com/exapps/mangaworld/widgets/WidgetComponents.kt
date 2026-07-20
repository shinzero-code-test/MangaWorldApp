package com.exapps.mangaworld.widgets
import androidx.glance.appwidget.stringResource


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
            .padding(14.dp)
    ) {
        if (showTitle) {
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
    Button(
        text = label,
        onClick = actionStartActivity(intent),
        modifier = GlanceModifier.fillMaxWidth().cornerRadius(14.dp)
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingDotActive != null) {
            WidgetStatusDot(active = leadingDotActive)
            Spacer(GlanceModifier.width(8.dp))
        }
        Column(modifier = GlanceModifier.fillMaxWidth()) {
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
internal fun WidgetEmptyState(title: String, subtitle: String, intent: Intent? = null, actionLabel: String? = null, retryIntent: Intent? = null) {
    Column(
        modifier = GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally
    ) {
        Box(
            modifier = GlanceModifier
                .size(56.dp)
                .background(GlanceTheme.colors.surfaceVariant)
                .cornerRadius(28.dp),
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
        if (retryIntent != null) {
            Spacer(GlanceModifier.height(6.dp))
            WidgetPrimaryButton(label = stringResource(com.exapps.mangaworld.R.string.widget_retry), intent = retryIntent)
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

@Composable
internal fun WidgetStatusDot(active: Boolean) {
    Box(
        modifier = GlanceModifier
            .size(7.dp)
            .background(if (active) GlanceTheme.colors.primary else GlanceTheme.colors.outline)
            .cornerRadius(4.dp)
    ) {}
}

