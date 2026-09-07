package com.exapps.mangaworld.domain

/**
 * Single source of truth for username rules.
 *
 * Previously copy-pasted into FirebaseCommunityRepository, LoginViewModel and
 * SignUpScreen — any rule tweak had to land in three places. Reference this
 * from all username validation paths.
 */
object UsernameRules {
    const val MIN_LENGTH = 3
    const val MAX_LENGTH = 20

    /** 3–20 chars: alphanumerics/underscore, no leading or trailing underscore. */
    val REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9_]{1,18}[a-zA-Z0-9]$")

    fun isValid(normalizedUsername: String): Boolean =
        normalizedUsername.length in MIN_LENGTH..MAX_LENGTH &&
            REGEX.matches(normalizedUsername)
}
