package com.exapps.mangaworld.core.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.annotation.StringRes
import com.exapps.mangaworld.R
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
        private const val KEY_PIN_ATTEMPTS = "pin_attempts"
        private const val KEY_PIN_LOCKOUT_UNTIL = "pin_lockout_until"

        /** Free tries before exponential lockout engages. */
        private const val FREE_PIN_ATTEMPTS = 5

        private const val HASH_PREFIX = "v2"
        private const val HASH_ALGORITHM = "PBKDF2WithHmacSHA256"
        private const val HASH_ITERATIONS = 120_000
        private const val HASH_KEY_BITS = 256
        private const val SALT_BYTES = 16

        /**
         * The salted PBKDF2 write path landed in v8.2.4 (2026-09-07), so EVERY
         * legacy row (a bare `String.hashCode()`) was written before that date.
         *
         * A numeric PIN has at most 10^4 candidates: a legacy row is a ≤10^4
         * brute force away from the PIN, so it is refused and the user is asked
         * to set a new PIN (the PBKDF2 path).
         *
         * The retirement is deliberately NOT gated on the device clock — the
         * user controls that clock, so a clock rolled back before the cut-off
         * would silently re-enable the legacy path. The cut-off is a
         * documentation constant and a test pin, not a runtime condition.
         */
        internal const val LEGACY_HASH_RETIRED_AT_MS = 1_788_739_200_000L // 2026-09-07T00:00:00Z
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setPin(pin: String) {
        require(pin.isNotBlank() && pin.trim().length >= 4) {
            context.getString(R.string.parental_pin_too_short)
        }
        prefs.edit()
            .putString(KEY_PIN_HASH, hashPin(pin))
            .remove(KEY_PIN_ATTEMPTS)
            .remove(KEY_PIN_LOCKOUT_UNTIL)
            .apply()
    }

    /**
     * Verifies the PIN.
     *
     * A stored LEGACY row (a bare `String.hashCode()`) NEVER unlocks: it is a
     * ≤10^4 brute force and every legacy row predates the v8.2.4 PBKDF2 write
     * path. Such a device must set a new PIN — see [requiresPinReset] and
     * [pinResetRequiredMessageRes].
     */
    fun verifyPin(pin: String): Boolean {
        // Brute-force throttle: a short numeric PIN is ~10k candidates, so the
        // hash cost alone is not enough friction. Exponential lockout past 5 fails.
        if (System.currentTimeMillis() < prefs.getLong(KEY_PIN_LOCKOUT_UNTIL, 0L)) return false
        val storedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        if (!storedHash.startsWith(HASH_PREFIX)) {
            // Retired legacy format: refuse WITHOUT burning a PIN attempt.
            // There is no brute force to slow down here (the path is closed),
            // and counting a failure would only lock the user out of a PIN
            // they can never unlock with.
            return false
        }
        val matched = verifySaltedPin(pin, storedHash)
        recordPinAttempt(matched)
        return matched
    }

    /**
     * True when a PIN row exists in the RETIRED legacy format. Such a row can
     * never be unlocked — the user has to set a new PIN (PBKDF2).
     *
     * Note: [hasPin] still reports true for such a row (a row exists); a
     * caller that wants to know whether the PIN is USABLE must ask this.
     */
    fun requiresPinReset(): Boolean {
        val storedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        return !storedHash.startsWith(HASH_PREFIX)
    }

    /**
     * The message to show when [requiresPinReset] is true. A string resource
     * (never hardcoded) so the caller renders it in the user's language.
     */
    @StringRes
    fun pinResetRequiredMessageRes(): Int = R.string.parental_pin_legacy_reset_required

    private fun recordPinAttempt(success: Boolean) {
        if (success) {
            prefs.edit().remove(KEY_PIN_ATTEMPTS).remove(KEY_PIN_LOCKOUT_UNTIL).apply()
            return
        }
        val attempts = prefs.getInt(KEY_PIN_ATTEMPTS, 0) + 1
        // 30s doubling from the 5th failure, capped at 30 minutes.
        val lockoutMs = if (attempts < FREE_PIN_ATTEMPTS) 0L
        else (30_000L shl (attempts - FREE_PIN_ATTEMPTS)).coerceAtMost(1_800_000L)
        prefs.edit()
            .putInt(KEY_PIN_ATTEMPTS, attempts)
            .putLong(KEY_PIN_LOCKOUT_UNTIL, System.currentTimeMillis() + lockoutMs)
            .apply()
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
     * reversible from any prefs backup. Each call mints a FRESH salt — the result
     * must only be stored, never compared directly (see [verifySaltedPin]).
     */
    private fun hashPin(pin: String): String {
        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = pbkdf2(pin.toCharArray(), salt)
        return HASH_PREFIX + Base64.encodeToString(salt, Base64.NO_WRAP) +
            ":" + Base64.encodeToString(hash, Base64.NO_WRAP)
    }

    /**
     * Re-derives the hash with the STORED salt and compares in constant time.
     * Malformed rows fail closed (false) rather than throwing.
     */
    private fun verifySaltedPin(pin: String, storedHash: String): Boolean {
        val body = storedHash.removePrefix(HASH_PREFIX)
        val separator = body.indexOf(':')
        if (separator <= 0) return false
        val salt = runCatching {
            Base64.decode(body.substring(0, separator), Base64.NO_WRAP)
        }.getOrNull() ?: return false
        if (salt.size != SALT_BYTES) return false
        val expected = body.substring(separator + 1)
        if (expected.isEmpty()) return false
        val actual = Base64.encodeToString(pbkdf2(pin.toCharArray(), salt), Base64.NO_WRAP)
        return constantTimeEquals(actual, expected)
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance(HASH_ALGORITHM).generateSecret(
            PBEKeySpec(password, salt, HASH_ITERATIONS, HASH_KEY_BITS)
        ).encoded

    /** Length-safe constant-time comparison to avoid leaking prefix matches by timing. */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
