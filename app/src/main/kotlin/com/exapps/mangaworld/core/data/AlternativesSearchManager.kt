package com.exapps.mangaworld.core.data

import com.exapps.mangaworld.core.source.plugins.SourceId
import com.exapps.mangaworld.core.source.plugins.SourceRegistry
import com.exapps.mangaworld.domain.model.MangaItem
import com.exapps.mangaworld.domain.repository.MangaRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlternativesSearchManager @Inject constructor(
    private val mangaRepository: MangaRepository,
    private val registry: SourceRegistry
) {
    suspend fun findAlternatives(
        title: String,
        currentSource: SourceId,
        enabledSources: Set<String>,
        limit: Int = 10
    ): List<MangaItem> = coroutineScope {
        val sources = registry.scraperMap().keys.filter {
            it in enabledSources && it != currentSource.value
        }.map { SourceId(it) }

        val deferredResults = sources.map { source ->
            async {
                try {
                    mangaRepository.searchMangaDirect(title, source, page = 1)
                        .getOrDefault(emptyList())
                        .filter { item ->
                            normalizeTitle(item.title).contains(normalizeTitle(title)) ||
                            normalizeTitle(title).contains(normalizeTitle(item.title))
                        }
                        .take(3)
                } catch (e: Exception) {
                    emptyList()
                }
            }
        }

        deferredResults.awaitAll()
            .flatten()
            .distinctBy { it.id }
            .sortedByDescending { it.rating ?: 0f }
            .take(limit)
    }

    private fun normalizeTitle(title: String): String {
        return title.lowercase()
            .replace("[\\u064B-\\u065F]".toRegex(), "")
            .replace("[^\\p{L}\\p{Nd}]".toRegex(), "")
    }
}
