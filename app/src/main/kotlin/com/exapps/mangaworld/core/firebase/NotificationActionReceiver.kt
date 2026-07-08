package com.exapps.mangaworld.core.firebase

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.exapps.mangaworld.core.data.local.dao.FavoriteDao
import com.exapps.mangaworld.core.data.local.entity.FavoriteEntity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Handles notification action button clicks (e.g. "Add to Favourite").
 * Registered in AndroidManifest.xml.
 */
@AndroidEntryPoint
class NotificationActionReceiver : BroadcastReceiver() {

    @Inject lateinit var favoriteDao: FavoriteDao

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ADD_FAVORITE -> {
                val mangaId = intent.getStringExtra(EXTRA_MANGA_ID) ?: return
                val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
                val sourceId = intent.getStringExtra(EXTRA_SOURCE_ID) ?: return
                val slug = intent.getStringExtra(EXTRA_SLUG) ?: ""
                val coverUrl = intent.getStringExtra(EXTRA_COVER_URL) ?: ""

                CoroutineScope(Dispatchers.IO).launch {
                    favoriteDao.insert(
                        FavoriteEntity(
                            mangaId = mangaId,
                            sourceId = sourceId,
                            title = title,
                            slug = slug,
                            coverUrl = coverUrl
                        )
                    )
                }

                // Dismiss the notification
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.cancel(intent.getIntExtra(EXTRA_NOTIFICATION_ID, 0))
            }
        }
    }

    companion object {
        const val ACTION_ADD_FAVORITE = "com.exapps.mangaworld.ADD_FAVORITE"
        const val EXTRA_MANGA_ID = "manga_id"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SOURCE_ID = "source_id"
        const val EXTRA_SLUG = "slug"
        const val EXTRA_COVER_URL = "cover_url"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
    }
}
