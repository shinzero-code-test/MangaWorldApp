package com.exapps.mangaworld.core.data

import com.exapps.mangaworld.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private fun domainCandidates(domain: String): List<String> {
    val normalized = domain.lowercase().trim().trim('.')
    if (normalized.isBlank()) return emptyList()

    val topPrivateDomain = "https://$normalized".toHttpUrlOrNull()?.topPrivateDomain()

    val result = mutableListOf<String>()
    var current = normalized
    while (current.isNotBlank()) {
        result += current
        if (current == topPrivateDomain) break
        val next = current.substringAfter('.', "")
        if (next.isBlank() || next == current) break
        current = next
    }
    return result
}

suspend fun resolveCookieForDomain(settingsRepo: SettingsRepository, domain: String): String? {
    CookieCache.get(domain)?.takeIf { it.isNotBlank() }?.let { return it }

    for (candidate in domainCandidates(domain)) {
        settingsRepo.getCookies(candidate).first()?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
}

suspend fun resolveCookieForUrl(settingsRepo: SettingsRepository, url: String): String? {
    val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
    if (host.isBlank()) return null
    return resolveCookieForDomain(settingsRepo, host)
}
