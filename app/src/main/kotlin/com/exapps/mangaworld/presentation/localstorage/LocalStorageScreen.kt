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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.exapps.mangaworld.core.data.download.DownloadQueueManager
import com.exapps.mangaworld.core.firebase.FirebaseAnalyticsManager
import com.exapps.mangaworld.core.firebase.FirebaseRemoteConfigManager
import com.exapps.mangaworld.core.firebase.withFirebaseTrace
import com.exapps.mangaworld.core.data.local.entity.DownloadedMangaEntity
import com.exapps.mangaworld.core.ml.MlKitCoverTagger
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.presentation.components.GradientDivider
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LocalStorageViewModel @Inject constructor(
    private val manager: DownloadQueueManager,
    private val coverTagger: MlKitCoverTagger,
    private val remoteConfigManager: FirebaseRemoteConfigManager,
    private val analyticsManager: FirebaseAnalyticsManager
) : ViewModel() {

    val downloadedMangas = manager.observeDownloadedMangas()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _confirmDelete = MutableStateFlow<DownloadedMangaEntity?>(null)
    val confirmDelete: StateFlow<DownloadedMangaEntity?> = _confirmDelete.asStateFlow()

    private val _autoTags = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val autoTags: StateFlow<Map<String, List<String>>> = _autoTags.asStateFlow()

    init {
        viewModelScope.launch {
            combine(manager.observeDownloadedMangas(), remoteConfigManager.mlCoverTaggingEnabled) { mangas, enabled ->
                mangas to enabled
            }.collectLatest { (mangas, enabled) ->
                if (!enabled) {
                    _autoTags.value = emptyMap()
                    return@collectLatest
                }

                val preserved = _autoTags.value.filterKeys { existingId ->
                    mangas.any { it.mangaId == existingId }
                }

                val missing = mangas.filter { it.mangaId !in preserved }
                val generated = coroutineScope {
                    missing.map { manga ->
                        async {
                            manga.mangaId to coverTagger.generateTags(manga.localCoverPath, manga.coverUrl)
                        }
                    }.associate { deferred -> deferred.await() }
                }.filterValues { it.isNotEmpty() }

                generated.forEach { (mangaId, tags) ->
                    mangas.firstOrNull { it.mangaId == mangaId }?.let { manga ->
                        analyticsManager.logCoverTagsGenerated(manga.sourceId, tags.size)
                    }
                }

                _autoTags.value = preserved + generated
            }
        }
    }

    fun promptDelete(manga: DownloadedMangaEntity) { _confirmDelete.value = manga }
    fun dismissDelete() { _confirmDelete.value = null }
    fun confirmDeleteManga() {
        val manga = _confirmDelete.value ?: return
        _confirmDelete.value = null
        viewModelScope.launch { manager.deleteDownloadedManga(manga.mangaId) }
    }
    fun chapterCount(entity: DownloadedMangaEntity): Int =
        manager.countDownloadedChapters(entity.mangaId, entity.title)

    fun tagsFor(entity: DownloadedMangaEntity): List<String> = autoTags.value[entity.mangaId].orEmpty()
}

@Composable
fun LocalStorageScreen(
    onMangaClick: (sourceId: String, slug: String) -> Unit,
    viewModel: LocalStorageViewModel = hiltViewModel()
) {
    val mangas by viewModel.downloadedMangas.collectAsStateWithLifecycle()
    val confirmDelete by viewModel.confirmDelete.collectAsStateWithLifecycle()
    val autoTags by viewModel.autoTags.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().background(MangaColors.Background)) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.FolderOpen, null,
                    tint = MangaColors.Primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
                Text("التخزين المحلي", style = MaterialTheme.typography.titleLarge,
                    color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                if (mangas.isNotEmpty()) {
                    Text("${mangas.size} مانجا", style = MaterialTheme.typography.bodySmall,
                        color = MangaColors.Muted)
                }
            }
            GradientDivider(Modifier.padding(horizontal = 16.dp))

            if (mangas.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        modifier = Modifier.padding(32.dp)) {
                        Icon(Icons.Filled.FolderOff, null,
                            modifier = Modifier.size(72.dp), tint = MangaColors.Muted)
                        Text("لا توجد مانجا محملة", style = MaterialTheme.typography.titleMedium,
                            color = MangaColors.Muted)
                        Text("نزّل فصولاً من صفحة التفاصيل لتظهر هنا",
                            style = MaterialTheme.typography.bodySmall, color = MangaColors.Muted)
                    }
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(mangas, key = { it.mangaId }) { manga ->
                        LocalMangaCard(
                            manga = manga,
                            autoTags = autoTags[manga.mangaId].orEmpty(),
                            downloadedChapters = viewModel.chapterCount(manga),
                            onClick = { onMangaClick(manga.sourceId, manga.slug) },
                            onDelete = { viewModel.promptDelete(manga) }
                        )
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }

        if (confirmDelete != null) {
            AlertDialog(
                onDismissRequest = viewModel::dismissDelete,
                containerColor = MangaColors.Surface,
                icon = {
                    Icon(Icons.Filled.DeleteForever, null,
                        tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(32.dp))
                },
                title = { Text("حذف التنزيلات", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) },
                text = {
                    Text("سيتم حذف جميع فصول \"${confirmDelete!!.title}\" من الجهاز نهائياً.",
                        color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                },
                confirmButton = {
                    Button(onClick = viewModel::confirmDeleteManga,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) { Text("حذف") }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissDelete) { Text("إلغاء", color = MangaColors.Muted) }
                }
            )
        }
    }
}

@Composable
private fun LocalMangaCard(
    manga: DownloadedMangaEntity,
    autoTags: List<String>,
    downloadedChapters: Int,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val ctx = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.CardBg),
        elevation = CardDefaults.cardElevation(3.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            AsyncImage(
                model = ImageRequest.Builder(ctx).data(manga.localCoverPath ?: manga.coverUrl)
                    .crossfade(true)
                    .withFirebaseTrace("local_cover")
                    .build(),
                imageLoader = ctx.imageLoader,
                contentDescription = manga.title, contentScale = ContentScale.Crop,
                modifier = Modifier.size(72.dp, 100.dp).clip(RoundedCornerShape(10.dp))
                    .background(MangaColors.SurfaceContainer)
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(manga.title, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, color = MangaColors.OnSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)

                Box(Modifier.background(MangaColors.SurfaceContainer, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)) {
                    Text(MangaSource.fromId(manga.sourceId).displayName,
                        style = MaterialTheme.typography.labelSmall, color = MangaColors.Cyan)
                }

                if (autoTags.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        autoTags.forEach { tag ->
                            Box(
                                Modifier
                                    .background(MangaColors.SurfaceContainer, RoundedCornerShape(100.dp))
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(tag, style = MaterialTheme.typography.labelSmall, color = MangaColors.OnSurfaceVariant)
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.DownloadDone, null,
                        modifier = Modifier.size(14.dp), tint = MangaColors.Primary)
                    Text(
                        if (manga.totalChapters > 0)
                            "$downloadedChapters / ${manga.totalChapters} فصل"
                        else "$downloadedChapters فصل محمل",
                        style = MaterialTheme.typography.bodySmall,
                        color = MangaColors.OnSurfaceVariant
                    )
                }

                if (manga.totalChapters > 0 && downloadedChapters > 0) {
                    LinearProgressIndicator(
                        progress = { (downloadedChapters.toFloat() / manga.totalChapters).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                        color = MangaColors.Primary, trackColor = MangaColors.SurfaceContainer
                    )
                }
            }

            IconButton(onClick = onDelete,
                modifier = Modifier.size(36.dp)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            ) {
                Icon(Icons.Filled.Delete, "حذف", modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}
