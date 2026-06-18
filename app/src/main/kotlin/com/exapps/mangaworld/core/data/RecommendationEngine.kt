package com.exapps.mangaworld.core.data

import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.dao.MangaCacheDao
import com.exapps.mangaworld.core.data.local.dao.ReadingHistoryDao
import com.exapps.mangaworld.domain.model.MangaItem
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecommendationEngine @Inject constructor(
    private val favoriteDao: FavoriteDao,
    private val historyDao: ReadingHistoryDao,
    private val cacheDao: MangaCacheDao
) {
    suspend fun getSmartRecommendations(
        candidates: List<MangaItem>,
        limit: Int = 12
    ): List<MangaItem> {
        if (candidates.isEmpty()) return emptyList()

        val favorites = favoriteDao.getFavoritesList()
        val history = historyDao.getAll()
        val favoriteIds = favorites.map { it.mangaId }.toSet()
        val historyIds = history.map { it.mangaId }.toSet()
        val readIds = favoriteIds + historyIds

        // Build genre affinity from favorites (weighted by recency)
        val favoriteGenres = buildGenreAffinity(favorites.map { it.mangaId })
        // Build genre affinity from reading history (weighted by recency)
        val historyGenres = buildGenreAffinity(history.map { it.mangaId })

        // Combine affinities: favorites count 2x, history counts 1x
        val combinedAffinity = mutableMapOf<String, Int>()
        (favoriteGenres.entries + historyGenres.entries).forEach { (genre, count) ->
            combinedAffinity[genre] = (combinedAffinity[genre] ?: 0) + count
        }

        // Get top genres (allow some diversity)
        val topGenres = combinedAffinity.entries
            .sortedByDescending { it.value }
            .take(6)
            .map { it.key }
            .toSet()

        // Score each candidate
        val scored = candidates
            .filterNot { it.id in readIds } // Exclude already read
            .map { manga ->
                val genreScore = manga.genres.count { it in topGenres }
                val sourceBonus = if (manga.source.id in favorites.map { it.source.id }) 2 else 0
                val ratingBonus = ((manga.rating ?: 0f) / 2f).toInt()
                manga to (genreScore + sourceBonus + ratingBonus)
            }
            .sortedByDescending { it.second }

        // Take top candidates with genre diversity
        val result = mutableListOf<MangaItem>()
        val usedGenres = mutableSetOf<String>()

        for ((manga, _) in scored) {
            if (result.size >= limit) break
            // Prefer genre diversity after first 4 items
            if (result.size >= 4) {
                val novelGenre = manga.genres.firstOrNull { it !in usedGenres }
                if (novelGenre != null) {
                    result.add(manga)
                    usedGenres.add(novelGenre)
                    continue
                }
            }
            result.add(manga)
        }

        return result
    }

    private suspend fun buildGenreAffinity(mangaIds: List<String>): Map<String, Int> {
        val affinity = mutableMapOf<String, Int>()
        val cached = cacheDao.getByIds(mangaIds)

        cached.forEach { cache ->
            runCatching {
                val arr = JSONArray(cache.genresJson)
                (0 until arr.length()).forEach { idx ->
                    val genre = arr.getString(idx)
                    // Recency weight: more recent = higher weight
                    val age = System.currentTimeMillis() - cache.cachedAt
                    val recencyWeight = when {
                        age < 7 * 24 * 60 * 60 * 1000L -> 3  // Last week
                        age < 30 * 24 * 60 * 60 * 1000L -> 2  // Last month
                        else -> 1
                    }
                    affinity[genre] = (affinity[genre] ?: 0) + recencyWeight
                }
            }
        }
        return affinity
    }
}
