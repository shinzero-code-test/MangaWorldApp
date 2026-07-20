package com.exapps.mangaworld.presentation.reader

import android.content.Context
import com.exapps.mangaworld.R
import androidx.annotation.StringRes

import androidx.compose.runtime.Immutable

enum class TapZone(@StringRes val labelRes: Int) {
    TOP_LEFT(R.string.str_025),
    TOP_CENTER(R.string.str_024),
    TOP_RIGHT(R.string.str_026),
    CENTER_LEFT(R.string.str_439),
    CENTER(R.string.center),
    CENTER_RIGHT(R.string.str_440),
    BOTTOM_LEFT(R.string.str_019),
    BOTTOM_CENTER(R.string.str_018),
    BOTTOM_RIGHT(R.string.str_020);

    fun getLabel(context: Context): String = context.getString(labelRes)
}

enum class TapAction(@StringRes val labelRes: Int) {
    PAGE_NEXT(R.string.next_page),
    PAGE_PREV(R.string.previous_page),
    CHAPTER_NEXT(R.string.reader_next),
    CHAPTER_PREV(R.string.reader_previous),
    TOGGLE_UI(R.string.str_071),
    SHOW_MENU(R.string.show_list),
    BOOKMARK(R.string.reference_mark),
    NONE(R.string.none);

    fun getLabel(context: Context): String = context.getString(labelRes)
}

@Immutable
data class TapGridConfig(
    val topLeft: TapAction = TapAction.PAGE_PREV,
    val topCenter: TapAction = TapAction.NONE,
    val topRight: TapAction = TapAction.CHAPTER_PREV,
    val centerLeft: TapAction = TapAction.PAGE_PREV,
    val center: TapAction = TapAction.TOGGLE_UI,
    val centerRight: TapAction = TapAction.PAGE_NEXT,
    val bottomLeft: TapAction = TapAction.CHAPTER_NEXT,
    val bottomCenter: TapAction = TapAction.NONE,
    val bottomRight: TapAction = TapAction.PAGE_NEXT
) {
    fun getActionForZone(zone: TapZone): TapAction = when (zone) {
        TapZone.TOP_LEFT -> topLeft
        TapZone.TOP_CENTER -> topCenter
        TapZone.TOP_RIGHT -> topRight
        TapZone.CENTER_LEFT -> centerLeft
        TapZone.CENTER -> center
        TapZone.CENTER_RIGHT -> centerRight
        TapZone.BOTTOM_LEFT -> bottomLeft
        TapZone.BOTTOM_CENTER -> bottomCenter
        TapZone.BOTTOM_RIGHT -> bottomRight
    }
}
