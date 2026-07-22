package com.exapps.mangaworld.core.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.exapps.mangaworld.widgets.ContinueReadingWidget
import com.exapps.mangaworld.widgets.DailyRecommendationsWidget
import com.exapps.mangaworld.widgets.LatestUpdatesWidget
import com.exapps.mangaworld.widgets.LibraryWidget
import com.exapps.mangaworld.widgets.ReadingStatsWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WidgetShortcutCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appShortcutManager: AppShortcutManager
) {
    suspend fun refreshWidgets() {
        ContinueReadingWidget().updateAll(context)
        DailyRecommendationsWidget().updateAll(context)
        LibraryWidget().updateAll(context)
        LatestUpdatesWidget().updateAll(context)
        ReadingStatsWidget().updateAll(context)
    }

    suspend fun refreshWidgetsAndShortcuts() {
        refreshWidgets()
        appShortcutManager.refreshDynamicShortcuts()
    }
}
