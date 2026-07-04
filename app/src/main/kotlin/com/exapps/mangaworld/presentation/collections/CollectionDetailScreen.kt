package com.exapps.mangaworld.presentation.collections

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.exapps.mangaworld.R
import com.exapps.mangaworld.core.data.CollectionManager
import com.exapps.mangaworld.core.data.MangaCollection
import com.exapps.mangaworld.core.data.local.dao.MangaCacheDao
import com.exapps.mangaworld.core.firebase.withFirebaseTrace
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CollectionDetailViewModel @Inject constructor(
    private val collectionManager: CollectionManager,
    private val mangaCacheDao: MangaCacheDao
) : ViewModel() {

    private val _collection = MutableStateFlow<MangaCollection?>(null)
    val collection: StateFlow<MangaCollection?> = _collection.asStateFlow()

    fun loadCollection(collectionId: String) {
        viewModelScope.launch {
            collectionManager.collections.collect { collections ->
                _collection.value = collections.find { it.id == collectionId }
            }
        }
    }

    fun removeMangaFromCollection(collectionId: String, mangaId: String) {
        viewModelScope.launch {
            collectionManager.removeMangaFromCollection(collectionId, mangaId)
        }
    }

    suspend fun getCachedManga(mangaId: String): com.exapps.mangaworld.core.data.local.entity.MangaCacheEntity? {
        return mangaCacheDao.get(mangaId)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionDetailScreen(
    collectionId: String,
    onBack: () -> Unit,
    onMangaClick: (sourceId: String, slug: String) -> Unit,
    viewModel: CollectionDetailViewModel = hiltViewModel()
) {
    LaunchedEffect(collectionId) { viewModel.loadCollection(collectionId) }
    val collection by viewModel.collection.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(collection?.name ?: "قائمة", color = MangaColors.OnSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "رجوع", tint = MangaColors.OnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MangaColors.Surface)
            )
        }
    ) { padding ->
        val mangaIds = collection?.mangaIds ?: emptyList()

        if (mangaIds.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("القائمة فارغة", style = MaterialTheme.typography.bodyLarge, color = MangaColors.OnSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("أضف مانجا من صفحة التفاصيل", style = MaterialTheme.typography.bodySmall, color = MangaColors.Muted)
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(mangaIds, key = { it }) { mangaId ->
                    CollectionMangaCard(
                        mangaId = mangaId,
                        onMangaClick = { onMangaClick(it.first, it.second) },
                        onRemove = { viewModel.removeMangaFromCollection(collectionId, mangaId) },
                        viewModel = viewModel
                    )
                }
            }
        }
    }
}

@Composable
private fun CollectionMangaCard(
    mangaId: String,
    onMangaClick: (Pair<String, String>) -> Unit,
    onRemove: () -> Unit,
    viewModel: CollectionDetailViewModel
) {
    var cached by remember { mutableStateOf<com.exapps.mangaworld.core.data.local.entity.MangaCacheEntity?>(null) }
    var showRemoveConfirm by remember { mutableStateOf(false) }
    val ctx = LocalContext.current

    LaunchedEffect(mangaId) {
        cached = viewModel.getCachedManga(mangaId)
    }

    val title = cached?.title ?: mangaId.substringAfter("_")
    val coverUrl = cached?.coverUrl ?: ""
    val sourceId = cached?.sourceId ?: mangaId.substringBefore("_")

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val slug = cached?.slug ?: mangaId.substringAfter("_")
                onMangaClick(sourceId to slug)
            },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.Surface)
    ) {
        Column(
            modifier = Modifier.padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MangaColors.SurfaceContainer),
                contentAlignment = Alignment.Center
            ) {
                if (coverUrl.isNotBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(ctx).data(coverUrl).crossfade(true).withFirebaseTrace("collection_cover").build(),
                        imageLoader = ctx.imageLoader,
                        contentDescription = title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(title.take(2), color = MangaColors.Primary, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                title,
                style = MaterialTheme.typography.labelSmall,
                color = MangaColors.OnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    if (showRemoveConfirm) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirm = false },
            containerColor = MangaColors.Surface,
            title = { Text("إزالة من القائمة", color = MangaColors.OnSurface) },
            text = { Text("إزالة \"$title\" من هذه القائمة؟", color = MangaColors.OnSurfaceVariant) },
            confirmButton = {
                Button(
                    onClick = { showRemoveConfirm = false; onRemove() },
                    colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Error)
                ) { Text("إزالة") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirm = false }) { Text("إلغاء", color = MangaColors.Muted) }
            }
        )
    }
}
