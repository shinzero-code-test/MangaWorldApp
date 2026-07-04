package com.exapps.mangaworld.presentation.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.exapps.mangaworld.core.firebase.CloudinaryUploader
import com.exapps.mangaworld.domain.model.CustomUserList
import com.exapps.mangaworld.domain.model.CustomUserListItem
import com.exapps.mangaworld.domain.repository.CommunityRepository
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UserListsViewModel @Inject constructor(
    private val communityRepository: CommunityRepository,
    private val cloudinaryUploader: CloudinaryUploader
) : ViewModel() {
    private val _selectedListId = MutableStateFlow<String?>(null)
    val lists = communityRepository.observeUserLists().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val selectedListId: StateFlow<String?> = _selectedListId.asStateFlow()
    val items = _selectedListId.flatMapLatest { id ->
        if (id == null) kotlinx.coroutines.flow.flowOf(emptyList()) else communityRepository.observeListItems(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun selectList(id: String?) { _selectedListId.value = id }
    fun saveList(listId: String?, name: String, description: String, coverUrl: String, rating: Float, genres: List<String>, isPublic: Boolean) {
        viewModelScope.launch {
            runCatching { communityRepository.createOrUpdateList(listId, name, description, coverUrl, rating, genres, isPublic) }
                .onSuccess { createdId -> _selectedListId.value = createdId }
        }
    }
    fun deleteList(id: String) { viewModelScope.launch { runCatching { communityRepository.deleteList(id) } } }
    fun removeManga(listId: String, mangaId: String) { viewModelScope.launch { runCatching { communityRepository.removeMangaFromList(listId, mangaId) } } }

    suspend fun uploadCover(uri: Uri): String? {
        return cloudinaryUploader.uploadImage(uri, folder = "list_covers")
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserListsScreen(
    onBack: () -> Unit,
    onListClick: (String) -> Unit = {},
    viewModel: UserListsViewModel = hiltViewModel()
) {
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val selectedListId by viewModel.selectedListId.collectAsStateWithLifecycle()
    var showEditor by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<CustomUserList?>(null) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isPublic by remember { mutableStateOf(false) }
    var coverUrl by remember { mutableStateOf("") }
    var rating by remember { mutableFloatStateOf(0f) }
    var genresText by remember { mutableStateOf("") }

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("قوائمي المخصصة", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "رجوع", tint = MangaColors.OnSurface)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        editTarget = null; name = ""; description = ""; isPublic = false
                        coverUrl = ""; rating = 0f; genresText = ""
                        showEditor = true
                    }) {
                        Icon(Icons.Filled.Add, "إضافة", tint = MangaColors.Cyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MangaColors.Surface)
            )
        }
    ) { padding ->
        if (lists.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("لا توجد قوائم بعد", color = MangaColors.OnSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        editTarget = null; name = ""; description = ""; isPublic = false
                        coverUrl = ""; rating = 0f; genresText = ""
                        showEditor = true
                    }) { Text("إنشاء قائمة جديدة") }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(lists, key = { it.id }) { list ->
                    ListCard(
                        list = list,
                        isExpanded = selectedListId == list.id,
                        items = if (selectedListId == list.id) items else emptyList(),
                        onExpand = { viewModel.selectList(if (selectedListId == list.id) null else list.id) },
                        onEdit = {
                            editTarget = list; name = list.name; description = list.description
                            isPublic = list.isPublic; coverUrl = list.coverUrl
                            rating = list.rating; genresText = list.genres.joinToString(", ")
                            showEditor = true
                        },
                        onDelete = { viewModel.deleteList(list.id) },
                        onRemoveItem = { mangaId -> viewModel.removeManga(list.id, mangaId) },
                        onClick = { onListClick(list.id) }
                    )
                }
            }
        }
    }

    if (showEditor) {
        ListEditorDialog(
            name = name, description = description, isPublic = isPublic,
            coverUrl = coverUrl, rating = rating, genresText = genresText,
            isEditing = editTarget != null,
            onNameChange = { name = it }, onDescriptionChange = { description = it },
            onPublicChange = { isPublic = it }, onCoverChange = { coverUrl = it },
            onRatingChange = { rating = it }, onGenresChange = { genresText = it },
            onUploadCover = { uri -> viewModel.uploadCover(uri) },
            onSave = {
                val genres = genresText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                viewModel.saveList(editTarget?.id, name, description, coverUrl, rating, genres, isPublic)
                showEditor = false
            },
            onDismiss = { showEditor = false }
        )
    }
}

@Composable
private fun ListCard(
    list: CustomUserList,
    isExpanded: Boolean,
    items: List<CustomUserListItem>,
    onExpand: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRemoveItem: (String) -> Unit,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isExpanded) MangaColors.GlowPurple else MangaColors.SurfaceContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Cover + Info
                Row(
                    modifier = Modifier.weight(1f).clickable(onClick = onClick),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (list.coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(list.coverUrl).crossfade(true).build(),
                            imageLoader = LocalContext.current.imageLoader,
                            contentDescription = list.name,
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MangaColors.Primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(list.name.take(1), color = MangaColors.Primary, fontWeight = FontWeight.Bold)
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(list.name, color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)
                        if (list.description.isNotBlank()) {
                            Text(
                                list.description,
                                color = MangaColors.OnSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${list.itemCount} عنصر", color = MangaColors.Cyan, style = MaterialTheme.typography.labelSmall)
                            if (list.rating > 0) {
                                Icon(Icons.Filled.Star, null, tint = MangaColors.Yellow, modifier = Modifier.size(12.dp))
                                Text(String.format("%.1f", list.rating), color = MangaColors.Yellow, style = MaterialTheme.typography.labelSmall)
                            }
                            if (list.genres.isNotEmpty()) {
                                Text(list.genres.take(2).joinToString(" • "), color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                // Actions
                Row {
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Edit, "تعديل", modifier = Modifier.size(18.dp), tint = MangaColors.Cyan)
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Delete, "حذف", modifier = Modifier.size(18.dp), tint = MangaColors.Error)
                    }
                }
            }

            // Expand toggle
            TextButton(onClick = onExpand, modifier = Modifier.fillMaxWidth()) {
                Text(if (isExpanded) "إخفاء العناصر" else "عرض العناصر (${list.itemCount})", color = MangaColors.Cyan)
            }

            // Items grid
            if (isExpanded && items.isNotEmpty()) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items, key = { it.mangaId }) { item ->
                        ListItemCard(item = item, onRemove = { onRemoveItem(item.mangaId) })
                    }
                }
            }
            if (isExpanded && items.isEmpty()) {
                Text("القائمة فارغة — أضف مانجا من صفحة التفاصيل", color = MangaColors.Muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ListItemCard(item: CustomUserListItem, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.Surface)
    ) {
        Column {
            if (item.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(item.coverUrl).crossfade(true).build(),
                    imageLoader = ctx.imageLoader,
                    contentDescription = item.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.7f)
                        .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.7f)
                        .background(MangaColors.SurfaceContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(item.title.take(2), color = MangaColors.Primary)
                }
            }
            Column(Modifier.padding(8.dp)) {
                Text(item.title, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                if (item.rating > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Star, null, tint = MangaColors.Yellow, modifier = Modifier.size(10.dp))
                        Text(String.format("%.1f", item.rating), color = MangaColors.Yellow, style = MaterialTheme.typography.labelSmall)
                    }
                }
                if (item.genres.isNotEmpty()) {
                    Text(item.genres.take(2).joinToString(", "), color = MangaColors.Muted,
                        style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.fillMaxWidth().height(28.dp)) {
                Icon(Icons.Filled.Delete, "إزالة", modifier = Modifier.size(14.dp), tint = MangaColors.Error)
            }
        }
    }
}

@Composable
private fun ListEditorDialog(
    name: String, description: String, isPublic: Boolean,
    coverUrl: String, rating: Float, genresText: String,
    isEditing: Boolean,
    onNameChange: (String) -> Unit, onDescriptionChange: (String) -> Unit,
    onPublicChange: (Boolean) -> Unit, onCoverChange: (String) -> Unit,
    onRatingChange: (Float) -> Unit, onGenresChange: (String) -> Unit,
    onUploadCover: suspend (Uri) -> String?,
    onSave: () -> Unit, onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    var isUploading by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            isUploading = true
            // Upload in background
            kotlinx.coroutines.MainScope().launch {
                val url = onUploadCover(it)
                if (url != null) onCoverChange(url)
                isUploading = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MangaColors.Surface,
        title = { Text(if (isEditing) "تعديل القائمة" else "إنشاء قائمة جديدة", color = MangaColors.OnSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = onNameChange,
                    label = { Text("اسم القائمة") }, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MangaColors.OnSurface, unfocusedTextColor = MangaColors.OnSurface))
                OutlinedTextField(value = description, onValueChange = onDescriptionChange,
                    label = { Text("الوصف") }, modifier = Modifier.fillMaxWidth(),
                    maxLines = 3, colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MangaColors.OnSurface, unfocusedTextColor = MangaColors.OnSurface))
                OutlinedTextField(value = genresText, onValueChange = onGenresChange,
                    label = { Text("التصنيفات (مفصولة بفاصلة)") }, modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MangaColors.OnSurface, unfocusedTextColor = MangaColors.OnSurface))

                // Cover
                Text("الغلاف", color = MangaColors.OnSurface, style = MaterialTheme.typography.labelMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { launcher.launch("image/*") }, enabled = !isUploading) {
                        Text(if (isUploading) "جاري الرفع..." else "اختر صورة")
                    }
                    OutlinedTextField(value = coverUrl, onValueChange = onCoverChange,
                        label = { Text("رابط الغلاف") }, modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MangaColors.OnSurface, unfocusedTextColor = MangaColors.OnSurface))
                }

                // Rating
                Text("التقييم", color = MangaColors.OnSurface, style = MaterialTheme.typography.labelMedium)
                Slider(value = rating, onValueChange = onRatingChange, valueRange = 0f..5f, steps = 9)
                Text(String.format("%.1f / 5.0", rating), color = MangaColors.Yellow)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isPublic, onCheckedChange = onPublicChange)
                    Text("قائمة عامة", color = MangaColors.OnSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave, enabled = name.isNotBlank() && !isUploading) { Text("حفظ") }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("إلغاء") } }
    )
}
