package com.exapps.mangaworld.presentation.reader

import androidx.compose.runtime.Immutable

enum class TapZone(val label: String) {
    TOP_LEFT("أعلى يسار"),
    TOP_CENTER("أعلى وسط"),
    TOP_RIGHT("أعلى يمين"),
    CENTER_LEFT("وسط يسار"),
    CENTER("وسط"),
    CENTER_RIGHT("وسط يمين"),
    BOTTOM_LEFT("أسفل يسار"),
    BOTTOM_CENTER("أسفل وسط"),
    BOTTOM_RIGHT("أسفل يمين")
}

enum class TapAction(val label: String) {
    PAGE_NEXT("الصفحة التالية"),
    PAGE_PREV("الصفحة السابقة"),
    CHAPTER_NEXT("الفصل التالي"),
    CHAPTER_PREV("الفصل السابق"),
    TOGGLE_UI("إظهار/إخفاء الأدوات"),
    SHOW_MENU("إظهار القائمة"),
    BOOKMARK("إشارة مرجعية"),
    NONE("لا شيء")
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
