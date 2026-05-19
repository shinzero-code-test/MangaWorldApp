package com.exapps.mangaworld.core.widget

import android.content.Context
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.exapps.mangaworld.R
import com.exapps.mangaworld.core.data.ContinueReadingWidgetData
import com.exapps.mangaworld.core.data.WidgetDataRepository
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppShortcutManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val widgetDataRepository: WidgetDataRepository
) {

    suspend fun refreshDynamicShortcuts() {
        val shortcuts = buildList {
            widgetDataRepository.getContinueReading()?.let { add(buildContinueReadingShortcut(it)) }
            widgetDataRepository.getRecentReadingTargets(limit = 3)
                .forEachIndexed { index, item -> add(buildRecentShortcut(index, item)) }
        }

        if (shortcuts.isEmpty()) {
            ShortcutManagerCompat.removeAllDynamicShortcuts(context)
        } else {
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }
    }

    private fun buildContinueReadingShortcut(item: ContinueReadingWidgetData): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, "continue_reading")
            .setShortLabel("تابع ${item.title}")
            .setLongLabel("تابع القراءة: ${item.title} - ${item.chapterLabel}")
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_continue))
            .setIntent(AppLaunchIntents.reader(context, item.sourceId, item.mangaId, item.chapterUrl))
            .setRank(0)
            .build()

    private fun buildRecentShortcut(index: Int, item: ContinueReadingWidgetData): ShortcutInfoCompat =
        ShortcutInfoCompat.Builder(context, "recent_${item.mangaId}")
            .setShortLabel(item.title.take(30))
            .setLongLabel("${item.title} - ${item.chapterLabel}")
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_recent))
            .setIntent(AppLaunchIntents.reader(context, item.sourceId, item.mangaId, item.chapterUrl))
            .setRank(index + 1)
            .build()
}
