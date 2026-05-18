package com.exapps.mangaworld.core.integration

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.exapps.mangaworld.core.data.WidgetDataRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class RandomShortcutActivity : ComponentActivity() {

    @Inject lateinit var widgetDataRepository: WidgetDataRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            val random = widgetDataRepository.getRandomMangaTarget()
            val intent = if (random != null) {
                AppLaunchIntents.detail(this@RandomShortcutActivity, random.sourceId, random.slug)
            } else {
                AppLaunchIntents.home(this@RandomShortcutActivity)
            }
            startActivity(intent)
            finish()
        }
    }
}
