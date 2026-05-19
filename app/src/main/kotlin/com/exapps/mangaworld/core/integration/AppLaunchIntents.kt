package com.exapps.mangaworld.core.integration

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.exapps.mangaworld.MainActivity

object AppLaunchIntents {
    private const val Scheme = "mangaworld"

    fun home(context: Context): Intent = intent(context, "$Scheme://screen/home")

    fun latestUpdates(context: Context): Intent = intent(context, "$Scheme://screen/latest_updates")

    fun search(context: Context): Intent = intent(context, "$Scheme://screen/search")

    fun downloads(context: Context): Intent = intent(context, "$Scheme://screen/downloads")

    fun detail(context: Context, sourceId: String, slug: String): Intent =
        intent(context, "$Scheme://manga/$sourceId/$slug")

    fun reader(context: Context, sourceId: String, mangaId: String, chapterUrl: String): Intent {
        val uri = Uri.Builder()
            .scheme(Scheme)
            .authority("reader")
            .appendQueryParameter("sourceId", sourceId)
            .appendQueryParameter("mangaId", mangaId)
            .appendQueryParameter("chapterUrl", chapterUrl)
            .build()
        return intent(context, uri)
    }

    fun randomShortcut(context: Context): Intent = Intent(context, RandomShortcutActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }

    private fun intent(context: Context, uri: String): Intent = intent(context, Uri.parse(uri))

    private fun intent(context: Context, uri: Uri): Intent = Intent(Intent.ACTION_VIEW, uri, context, MainActivity::class.java).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
