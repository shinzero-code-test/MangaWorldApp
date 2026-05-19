package com.exapps.mangaworld.core.data

import android.content.Context
import coil.imageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CacheManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val coilDir: File = File(context.cacheDir, "coil_image_cache")

    suspend fun getImageCacheSizeBytes(): Long = withContext(Dispatchers.IO) {
        dirSize(coilDir)
    }

    suspend fun clearImageCache() = withContext(Dispatchers.IO) {
        runCatching { context.imageLoader.diskCache?.clear() }
        if (coilDir.exists()) coilDir.deleteRecursively()
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
    }
}
