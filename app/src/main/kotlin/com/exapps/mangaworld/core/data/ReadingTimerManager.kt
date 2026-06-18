package com.exapps.mangaworld.core.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class ReadingTimerState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,
    val elapsedSeconds: Long = 0L,
    val targetMinutes: Int = 25,
    val isBreak: Boolean = false,
    val breakMinutes: Int = 5,
    val sessionsCompleted: Int = 0
) {
    val progressPercent: Float get() {
        val totalSeconds = if (isBreak) breakMinutes * 60L else targetMinutes * 60L
        return if (totalSeconds > 0) (elapsedSeconds.toFloat() / totalSeconds).coerceIn(0f, 1f) else 0f
    }
    val remainingSeconds: Long get() {
        val totalSeconds = if (isBreak) breakMinutes * 60L else targetMinutes * 60L
        return (totalSeconds - elapsedSeconds).coerceAtLeast(0L)
    }
    val displayTime: String get() {
        val minutes = remainingSeconds / 60
        val seconds = remainingSeconds % 60
        return "%02d:%02d".format(minutes, seconds)
    }
}

@Singleton
class ReadingTimerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _state = MutableStateFlow(ReadingTimerState())
    val state: StateFlow<ReadingTimerState> = _state.asStateFlow()

    private var timerJob: kotlinx.coroutines.Job? = null

    fun startTimer(targetMinutes: Int = 25, breakMinutes: Int = 5) {
        _state.value = ReadingTimerState(
            isRunning = true,
            targetMinutes = targetMinutes,
            breakMinutes = breakMinutes
        )
    }

    fun pauseTimer() {
        _state.value = _state.value.copy(isPaused = true)
    }

    fun resumeTimer() {
        _state.value = _state.value.copy(isPaused = false)
    }

    fun stopTimer() {
        _state.value = ReadingTimerState()
    }

    fun updateElapsed(seconds: Long) {
        val current = _state.value
        if (!current.isRunning || current.isPaused) return

        val newElapsed = current.elapsedSeconds + seconds
        val totalSeconds = if (current.isBreak) current.breakMinutes * 60L else current.targetMinutes * 60L

        if (newElapsed >= totalSeconds) {
            // Timer completed
            if (current.isBreak) {
                // Break finished, start new reading session
                _state.value = current.copy(
                    isBreak = false,
                    elapsedSeconds = 0L,
                    sessionsCompleted = current.sessionsCompleted + 1
                )
            } else {
                // Reading session finished, start break
                _state.value = current.copy(
                    isBreak = true,
                    elapsedSeconds = 0L
                )
            }
        } else {
            _state.value = current.copy(elapsedSeconds = newElapsed)
        }
    }

    fun skipBreak() {
        val current = _state.value
        if (current.isBreak) {
            _state.value = current.copy(
                isBreak = false,
                elapsedSeconds = 0L,
                sessionsCompleted = current.sessionsCompleted + 1
            )
        }
    }
}
