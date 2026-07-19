package com.exapps.mangaworld.presentation.localstorage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.exapps.mangaworld.core.data.local.dao.DownloadedMangaDao
import com.exapps.mangaworld.core.data.local.entity.DownloadedMangaEntity
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class LocalMangaDetailViewModel @Inject constructor(
    private val downloadedMangaDao: DownloadedMangaDao
) : ViewModel() {
    var manga by mutableStateOf<DownloadedMangaEntity?>(null)
        private set
    var chapters by mutableStateOf<List<Pair<String, File>>>(emptyList())
        private set

    fun load(mangaId: String, externalFilesDir: File?) {
        viewModelScope.launch {
            manga = downloadedMangaDao.get(mangaId)
            val downloadsDir = File(externalFilesDir, "downloads")
            val mangaDir = File(downloadsDir, mangaId)
            if (mangaDir.exists()) {
                chapters = mangaDir.listFiles()
                    ?.filter { it.isDirectory && !it.name.startsWith(".") }
                    ?.sortedBy { it.name?.replace("[^0-9.]".toRegex(), "")?.toFloatOrNull() ?: 0f }
                    ?.map { it.name!! to it }
                    .orEmpty()
            }
        }
    }
}

/**
 * Simple detail screen for imported/downloaded manga.
 * Shows available chapters and allows reading them locally.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalMangaDetailScreen(
    mangaId: String,
    onBack: () -> Unit,
    onReadChapter: (chapterPath: String) -> Unit = {},
    viewModel: LocalMangaDetailViewModel = hiltViewModel()
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(mangaId) {
        val externalFilesDir = (context.applicationContext as android.app.Application).getExternalFilesDir(null)
        viewModel.load(mangaId, externalFilesDir)
    }

    val manga = viewModel.manga
    val chapters = viewModel.chapters

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(manga?.title ?: "مانجا محلية", color = MangaColors.OnSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "رجوع", tint = MangaColors.OnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MangaColors.Surface)
            )
        }
    ) { padding ->
        if (manga == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MangaColors.Cyan)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Cover + info
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        manga.localCoverPath?.let { path ->
                            if (File(path).exists()) {
                                AsyncImage(
                                    model = File(path),
                                    contentDescription = manga.title,
                                    modifier = Modifier.size(120.dp, 180.dp)
                                        .clip(RoundedCornerShape(12.dp)),
                                    contentScale = ContentScale.Crop
                                )
                            }
                        }
                        Column(Modifier.weight(1f)) {
                            Text(
                                manga.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = MangaColors.OnSurface,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "${manga.downloadedChapters} فصل متاح",
                                style = MaterialTheme.typography.bodySmall,
                                color = MangaColors.Cyan
                            )
                            if (manga.description.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    manga.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MangaColors.OnSurfaceVariant
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider(color = MangaColors.SurfaceHigh)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "الفصول",
                        style = MaterialTheme.typography.titleSmall,
                        color = MangaColors.OnSurface,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (chapters.isEmpty()) {
                    item {
                        Text(
                            "لا توجد فصول محملة",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MangaColors.Muted,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                } else {
                    items(chapters, key = { it.first }) { (name, dir) ->
                        val pageCount = dir.listFiles()?.filter {
                            it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp")
                        }?.size ?: 0

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().clickable { onReadChapter(dir.absolutePath) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.Article, null, tint = MangaColors.Primary)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MangaColors.OnSurface
                                    )
                                    Text(
                                        "$pageCount صفحة",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MangaColors.Muted
                                    )
                                }
                                Icon(Icons.Filled.ChevronRight, null, tint = MangaColors.Muted)
                            }
                        }
                    }
                }
            }
        }
    }
}
