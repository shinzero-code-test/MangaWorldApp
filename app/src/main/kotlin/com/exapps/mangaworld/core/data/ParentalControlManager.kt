package com.exapps.mangaworld.core.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ParentalControlManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("parental_control_prefs", Context.MODE_PRIVATE)
    }

    companion object {
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_MUTED_GENRES = "muted_genres"
        private const val KEY_LOCKED_MANGA = "locked_manga"
        private const val KEY_MAX_READING_MINUTES = "max_reading_minutes"
        private const val KEY_READING_TIME_TODAY = "reading_time_today"
        private const val KEY_READING_DATE = "reading_date"
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setPin(pin: String) {
        prefs.edit().putString(KEY_PIN_HASH, hashPin(pin)).apply()
    }

    fun verifyPin(pin: String): Boolean {
        val storedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        return hashPin(pin) == storedHash
    }

    fun hasPin(): Boolean = prefs.getString(KEY_PIN_HASH, null) != null

    fun clearPin() {
        prefs.edit().remove(KEY_PIN_HASH).apply()
    }

    fun muteGenre(genre: String) {
        val muted = getMutedGenres().toMutableSet()
        muted.add(genre)
        prefs.edit().putStringSet(KEY_MUTED_GENRES, muted).apply()
    }

    fun unmuteGenre(genre: String) {
        val muted = getMutedGenres().toMutableSet()
        muted.remove(genre)
        prefs.edit().putStringSet(KEY_MUTED_GENRES, muted).apply()
    }

    fun getMutedGenres(): Set<String> = prefs.getStringSet(KEY_MUTED_GENRES, emptySet()) ?: emptySet()

    fun isGenreMuted(genre: String): Boolean = genre in getMutedGenres()

    fun lockManga(mangaId: String) {
        val locked = getLockedMangaIds().toMutableSet()
        locked.add(mangaId)
        prefs.edit().putStringSet(KEY_LOCKED_MANGA, locked).apply()
    }

    fun unlockManga(mangaId: String) {
        val locked = getLockedMangaIds().toMutableSet()
        locked.remove(mangaId)
        prefs.edit().putStringSet(KEY_LOCKED_MANGA, locked).apply()
    }

    fun getLockedMangaIds(): Set<String> = prefs.getStringSet(KEY_LOCKED_MANGA, emptySet()) ?: emptySet()

    fun isMangaLocked(mangaId: String): Boolean = mangaId in getLockedMangaIds()

    fun setMaxReadingMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_MAX_READING_MINUTES, minutes).apply()
    }

    fun getMaxReadingMinutes(): Int = prefs.getInt(KEY_MAX_READING_MINUTES, 0)

    fun recordReadingTime(minutes: Int) {
        val today = java.time.LocalDate.now().toString()
        val storedDate = prefs.getString(KEY_READING_DATE, "")
        val storedMinutes = prefs.getInt(KEY_READING_TIME_TODAY, 0)

        if (storedDate == today) {
            prefs.edit().putInt(KEY_READING_TIME_TODAY, storedMinutes + minutes).apply()
        } else {
            prefs.edit()
                .putString(KEY_READING_DATE, today)
                .putInt(KEY_READING_TIME_TODAY, minutes)
                .apply()
        }
    }

    fun getReadingTimeToday(): Int {
        val today = java.time.LocalDate.now().toString()
        val storedDate = prefs.getString(KEY_READING_DATE, "")
        return if (storedDate == today) prefs.getInt(KEY_READING_TIME_TODAY, 0) else 0
    }

    fun isReadingTimeExceeded(): Boolean {
        val maxMinutes = getMaxReadingMinutes()
        if (maxMinutes <= 0) return false
        return getReadingTimeToday() >= maxMinutes
    }

    private fun hashPin(pin: String): String {
        return pin.hashCode().toString()
    }
}
