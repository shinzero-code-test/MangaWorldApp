package com.exapps.mangaworld.widgets

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class WidgetSize(val label: String, val widthDp: Int, val heightDp: Int) {
    SMALL("صغير (1x2)", 180, 180),
    MEDIUM("متوسط (2x2)", 240, 220),
    LARGE("كبير (4x2)", 320, 280)
}

enum class WidgetTheme(val label: String) {
    SYSTEM("تلقائي"),
    LIGHT("فاتح"),
    DARK("داكن"),
    MONOCHROME("أحادي اللون")
}

@Singleton
class WidgetSettingsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("widget_settings", Context.MODE_PRIVATE)
    }

    companion object {
        private const val KEY_WIDGET_SIZE = "widget_size"
        private const val KEY_WIDGET_THEME = "widget_theme"
        private const val KEY_SHOW_COVERS = "show_covers"
        private const val KEY_SHOW_TITLES = "show_titles"
        private const val KEY_SHOW_NEW_BADGE = "show_new_badge"
        private const val KEY_TRANSPARENT_BG = "transparent_bg"
        private const val KEY_COMPACT_MODE = "compact_mode"
    }

    fun getWidgetSize(): WidgetSize {
        val name = prefs.getString(KEY_WIDGET_SIZE, WidgetSize.MEDIUM.name)
        return try { WidgetSize.valueOf(name!!) } catch (e: Exception) { WidgetSize.MEDIUM }
    }

    fun setWidgetSize(size: WidgetSize) {
        prefs.edit().putString(KEY_WIDGET_SIZE, size.name).apply()
    }

    fun getWidgetTheme(): WidgetTheme {
        val name = prefs.getString(KEY_WIDGET_THEME, WidgetTheme.SYSTEM.name)
        return try { WidgetTheme.valueOf(name!!) } catch (e: Exception) { WidgetTheme.SYSTEM }
    }

    fun setWidgetTheme(theme: WidgetTheme) {
        prefs.edit().putString(KEY_WIDGET_THEME, theme.name).apply()
    }

    fun isShowCovers(): Boolean = prefs.getBoolean(KEY_SHOW_COVERS, true)
    fun setShowCovers(show: Boolean) { prefs.edit().putBoolean(KEY_SHOW_COVERS, show).apply() }

    fun isShowTitles(): Boolean = prefs.getBoolean(KEY_SHOW_TITLES, true)
    fun setShowTitles(show: Boolean) { prefs.edit().putBoolean(KEY_SHOW_TITLES, show).apply() }

    fun isShowNewBadge(): Boolean = prefs.getBoolean(KEY_SHOW_NEW_BADGE, true)
    fun setShowNewBadge(show: Boolean) { prefs.edit().putBoolean(KEY_SHOW_NEW_BADGE, show).apply() }

    fun isTransparentBg(): Boolean = prefs.getBoolean(KEY_TRANSPARENT_BG, false)
    fun setTransparentBg(transparent: Boolean) { prefs.edit().putBoolean(KEY_TRANSPARENT_BG, transparent).apply() }

    fun isCompactMode(): Boolean = prefs.getBoolean(KEY_COMPACT_MODE, false)
    fun setCompactMode(compact: Boolean) { prefs.edit().putBoolean(KEY_COMPACT_MODE, compact).apply() }

    fun getVisibleItemCount(widgetHeightDp: Int): Int {
        val compact = isCompactMode()
        return when {
            widgetHeightDp < 200 -> if (compact) 2 else 1
            widgetHeightDp < 250 -> if (compact) 3 else 2
            else -> if (compact) 5 else 3
        }
    }
}
