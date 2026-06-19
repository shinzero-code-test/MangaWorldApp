package com.exapps.mangaworld.core.widget

import com.exapps.mangaworld.core.data.WidgetDataRepository
import com.exapps.mangaworld.widgets.WidgetSettingsManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetDataRepository(): WidgetDataRepository
    fun widgetSettingsManager(): WidgetSettingsManager
}
