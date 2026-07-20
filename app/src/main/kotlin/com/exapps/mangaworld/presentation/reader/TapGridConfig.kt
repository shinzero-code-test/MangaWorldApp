package com.exapps.mangaworld.presentation.reader

import android.content.Context
import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

import androidx.compose.runtime.Immutable

enum class TapZone(val label: String) {
    TOP_LEFT(context.getString(R.string.str_025)),
    TOP_CENTER(context.getString(R.string.str_024)),
    TOP_RIGHT(context.getString(R.string.str_026)),
    CENTER_LEFT(context.getString(R.string.str_439)),
    CENTER(context.getString(R.string.center)),
    CENTER_RIGHT(context.getString(R.string.str_440)),
    BOTTOM_LEFT(context.getString(R.string.str_019)),
    BOTTOM_CENTER(context.getString(R.string.str_018)),
    BOTTOM_RIGHT(context.getString(R.string.str_020))
}

enum class TapAction(val label: String) {
    PAGE_NEXT(context.getString(R.string.next_page)),
    PAGE_PREV(context.getString(R.string.previous_page)),
    CHAPTER_NEXT(context.getString(R.string.reader_next)),
    CHAPTER_PREV(context.getString(R.string.reader_previous)),
    TOGGLE_UI(context.getString(R.string.str_071)),
    SHOW_MENU(context.getString(R.string.show_list)),
    BOOKMARK(context.getString(R.string.reference_mark)),
    NONE(context.getString(R.string.none))
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
