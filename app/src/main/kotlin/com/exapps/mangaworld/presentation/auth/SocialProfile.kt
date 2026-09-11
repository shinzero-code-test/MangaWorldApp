package com.exapps.mangaworld.presentation.auth

import android.util.Log
import com.exapps.mangaworld.domain.UsernameRules
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.google.firebase.auth.FirebaseUser

/**
 * Social-signup profile provisioning (signup-with-Google/Facebook/Apple shows
 * a blank "guest" profile when this never runs — every sign-in entry point
 * must call [CommunityRepository.ensureSocialProfile]).
 *
 * Contract: display name = provider account name (email prefix when blank),
 * username = sanitized email local-part until the user changes it.
 */
fun usernameFromEmail(email: String?, uid: String): String {
    val local = email?.substringBefore('@')?.lowercase().orEmpty()
    val cleaned = local
        .map { ch -> if (ch in 'a'..'z' || ch in '0'..'9') ch else '_' }
        .joinToString("")
        .replace(Regex("_+"), "_")
        .trim('_')
        .take(UsernameRules.MAX_LENGTH)
        .trim('_')
    if (UsernameRules.isValid(cleaned)) return cleaned
    // Too short / empty after sanitizing (e.g. "@x.io", CJK-only local part):
    // derive from the uid, which is always ASCII alphanumeric.
    val uidPart = uid.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.takeLast(6)
    val fallback = "user_$uidPart"
    if (UsernameRules.isValid(fallback)) return fallback
    return "user_${(10000..99999).random()}"
}

/**
 * Creates the Firestore profile for a fresh social sign-in, or repairs a
 * blank-username profile left behind by an older build. No-op when a username
 * is already set. Never throws — callers must not fail sign-in over this.
 *
 * @return true when a username was already set or provisioning succeeded.
 */
suspend fun CommunityRepository.ensureSocialProfile(
    firebaseUser: FirebaseUser?,
    uid: String
): Boolean {
    val existing = runCatching { getCurrentProfile() }.getOrNull()
    if (existing != null && existing.username.isNotBlank()) return true
    val email = firebaseUser?.email
    val providerName = firebaseUser?.displayName?.takeIf { it.isNotBlank() }
    val displayName = (providerName
        ?: email?.substringBefore('@')?.takeIf { it.isNotBlank() }
        ?: "").trim()
    // Truncated base leaves room for the _NNNN collision suffix (15+1+4 = 20).
    val base = usernameFromEmail(email, uid)
    var candidate = base
    repeat(3) {
        val ok = runCatching {
            upsertProfile(
                username = candidate,
                bio = existing?.bio ?: "",
                isPublic = existing?.isPublic ?: true,
                avatarUrl = existing?.avatarUrl,
                bannerUrl = existing?.bannerUrl,
                displayName = displayName.ifBlank { existing?.displayName ?: "" },
                location = existing?.location ?: "",
                birthday = existing?.birthday
            )
        }.isSuccess
        if (ok) return true
        candidate = "${base.take(15)}_${(1000..9999).random()}"
    }
    // runCatching: android.util.Log is a stub that throws on plain JVM unit tests.
    runCatching { Log.w("SocialProfile", "provisioning failed for uid=$uid (base=$base)") }
    return false
}
