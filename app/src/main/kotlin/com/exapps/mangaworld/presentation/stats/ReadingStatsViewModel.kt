package com.exapps.mangaworld.presentation.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.data.DailyStat
import com.exapps.mangaworld.core.data.ReadingStatsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

data class ReadingStatsUiState(
    val totalReadingTimeMs: Long = 0L,
    val currentStreak: Int = 0,
    val longestStreak: Int = 0,
    val totalMangaRead: Int = 0,
    val dailyStats: List<DailyStat> = emptyList(),
    val isLoading: Boolean = true
) {
    val totalReadingHours: Int get() = (totalReadingTimeMs / 3_600_000).toInt()
    val totalReadingMinutes: Int get() = ((totalReadingTimeMs % 3_600_000) / 60_000).toInt()
    val averagePagesPerDay: Int get() {
        if (dailyStats.isEmpty()) return 0
        val totalPages = dailyStats.sumOf { it.pagesRead }
        // Use 30 days as denominator (or actual days if less data), not active days
        val days = dailyStats.size.coerceAtMost(30)
        return totalPages / days
    }
    val todayPages: Int get() {
        val today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        return dailyStats.find { it.date == today }?.pagesRead ?: 0
    }
    val thisWeekPages: Int get() {
        val weekAgo = java.time.LocalDate.now().minusDays(7)
            .format(java.time.format.DateTimeFormatter.ISO_LOCAL_DATE)
        return dailyStats.filter { it.date >= weekAgo }.sumOf { it.pagesRead }
    }
}

@HiltViewModel
class ReadingStatsViewModel @Inject constructor(
    private val statsStore: ReadingStatsStore
) : ViewModel() {

    val state: StateFlow<ReadingStatsUiState> = combine(
        statsStore.totalReadingTimeMs,
        statsStore.currentStreak,
        statsStore.longestStreak,
        statsStore.totalMangaRead,
        statsStore.dailyStats
    ) { total, streak, longest, manga, daily ->
        ReadingStatsUiState(
            totalReadingTimeMs = total,
            currentStreak = streak,
            longestStreak = longest,
            totalMangaRead = manga,
            dailyStats = daily,
            isLoading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReadingStatsUiState())
}
