package com.exapps.mangaworld.core.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
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

        private const val HASH_PREFIX = "v2"
        private const val HASH_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val HASH_ITERATIONS = 120_000
        private const val HASH_KEY_BITS = 256
        private const val SALT_BYTES = 16
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
        return if (storedHash.startsWith(HASH_PREFIX)) {
            constantTimeEquals(hashPin(pin), storedHash)
        } else {
            // Legacy rows stored a bare String.hashCode() — verify against it, then
            // transparently upgrade to the salted PBKDF2 format on first success.
            val legacyMatches = pin.hashCode().toString() == storedHash
            if (legacyMatches) {
                prefs.edit().putString(KEY_PIN_HASH, hashPin(pin)).apply()
            }
            legacyMatches
        }
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

    /**
     * Derives a salted PBKDF2-SHA256 hash formatted as "v2<base64salt>:<base64hash>".
     * A numeric PIN has at most 10^4 candidates, so unsalted fast hashes are trivially
     * reversible from any prefs backup.
     */
    private fun hashPin(pin: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin.toCharArray(), salt)
        return HASH_PREFIX + Base64.encodeToString(salt, Base64.NO_WRAP) +
            ":" + Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance(HASH_ALGORITHM).generateSecret(
            PBEKeySpec(password, salt, HASH_ITERATIONS, HASH_KEY_BITS)
        ).encoded

    /** Length-safe constant-time comparison to avoid leaking prefix matches by timing. */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
