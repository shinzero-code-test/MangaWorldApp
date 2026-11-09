package com.exapps.mangaworld.core.data

import android.content.Context

/**
 * U-36: synchronous mirror of the `imageCacheLimitMb` app setting for the
 * Coil builders. The setting lives in DataStore (`AppPreferences`), which
 * cannot be read synchronously at startup — and both ImageLoader builders
 * (`MangaWorldApp.newImageLoader`, `Modules.provideImageLoader`) run once
 * before any coroutine collection. `AppPreferences.setImageCacheLimitMb`
 * (the single choke point for settings UI, cloud-sync apply and backup
 * restore) mirrors the value here; the builders read it synchronously.
 * If the mirror is ever behind, the next set self-heals it.
 */
object ImageCacheLimitStore {

    /** Mirrors AppSettings default + AppPreferences fallback. */
    const val DEFAULT_IMAGE_CACHE_MB = 250

    private const val PREFS_NAME = "app_cache_settings"
    private const val KEY_IMAGE_CACHE_LIMIT_MB = "image_cache_limit_mb"

    /** Settings UI offers a fixed set of sizes; clamp defensively anyway. */
    const val MIN_IMAGE_CACHE_MB = 50
    const val MAX_IMAGE_CACHE_MB = 2000

    fun readMb(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_IMAGE_CACHE_LIMIT_MB, DEFAULT_IMAGE_CACHE_MB)
            .coerceIn(MIN_IMAGE_CACHE_MB, MAX_IMAGE_CACHE_MB)

    fun writeMb(context: Context, limitMb: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_IMAGE_CACHE_LIMIT_MB, limitMb.coerceIn(MIN_IMAGE_CACHE_MB, MAX_IMAGE_CACHE_MB))
            .apply()
    }
}
