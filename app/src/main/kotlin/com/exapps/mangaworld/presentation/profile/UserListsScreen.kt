package com.exapps.mangaworld.presentation.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlin.math.roundToInt
import javax.inject.Inject

// =====================================================================================
// ViewModel — business logic is unchanged from the original implementation.
// =====================================================================================

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
            val existing = listId?.let { id -> lists.value.find { it.id == id } }
            val createdId = runCatching {
                communityRepository.createOrUpdateList(listId, name, description, coverUrl, rating, genres, isPublic)
            }.getOrNull() ?: return@launch
            if (existing != null && existing.coverUrl != coverUrl && existing.coverUrl.isNotBlank()) {
                cloudinaryUploader.extractPublicId(existing.coverUrl)?.let { id -> cloudinaryUploader.deleteImage(id) }
            }
            _selectedListId.value = createdId
        }
    }
    fun deleteList(id: String) {
        viewModelScope.launch {
            val list = lists.value.find { it.id == id }
            if (runCatching { communityRepository.deleteList(id) }.isSuccess) {
                list?.coverUrl?.takeIf { it.isNotBlank() }
                    ?.let { url -> cloudinaryUploader.extractPublicId(url) }
                    ?.let { id -> cloudinaryUploader.deleteImage(id) }
            }
        }
    }
    fun removeManga(listId: String, mangaId: String) { viewModelScope.launch { runCatching { communityRepository.removeMangaFromList(listId, mangaId) } } }

    suspend fun uploadCover(uri: Uri): CloudinaryUploader.UploadResult? {
        return cloudinaryUploader.uploadImage(uri, assetType = "list-cover")
    }
}

// =====================================================================================
// Screen
// =====================================================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserListsScreen(
    onBack: () -> Unit,
    onListClick: (String) -> Unit = {},
    onItemClick: (sourceId: String, slug: String) -> Unit = { _, _ -> },
    viewModel: UserListsViewModel = hiltViewModel()
) {
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val listItems by viewModel.items.collectAsStateWithLifecycle()
    val selectedListId by viewModel.selectedListId.collectAsStateWithLifecycle()
    var showEditor by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<CustomUserList?>(null) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isPublic by remember { mutableStateOf(false) }
    var coverUrl by remember { mutableStateOf("") }
    var rating by remember { mutableFloatStateOf(0f) }
    var genresText by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<CustomUserList?>(null) }

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MangaColors.Background)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = MangaColors.OnSurface)
                        }
                        Text(
                            "قوائمي المخصصة",
                            color = MangaColors.OnSurface,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(percent = 50))
                            .background(MangaColors.Cyan)
                            .clickable(onClick = {
                                editTarget = null; name = ""; description = ""; isPublic = false
                                coverUrl = ""; rating = 0f; genresText = ""
                                showEditor = true
                            })
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, tint = MangaColors.Background, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("جديدة", color = MangaColors.Background, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MangaColors.OnSurface.copy(alpha = 0.06f))
                )
            }
        }
    ) { padding ->
        if (lists.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(MangaColors.GlowCyan),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Filled.ListAlt, contentDescription = null, tint = MangaColors.Cyan, modifier = Modifier.size(30.dp))
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "لا توجد قوائم بعد",
                        color = MangaColors.OnSurface,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "أنشئ قائمتك الأولى لتنظيم المانجا المفضلة لديك",
                        color = MangaColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = {
                            editTarget = null; name = ""; description = ""; isPublic = false
                            coverUrl = ""; rating = 0f; genresText = ""
                            showEditor = true
                        },
                        shape = RoundedCornerShape(percent = 50),
                        colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Cyan, contentColor = MangaColors.Background)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("إنشاء قائمة جديدة", fontWeight = FontWeight.Bold)
                    }
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
                        listItems = if (selectedListId == list.id) listItems else emptyList(),
                        onExpand = { viewModel.selectList(if (selectedListId == list.id) null else list.id) },
                        onEdit = {
                            editTarget = list; name = list.name; description = list.description
                            isPublic = list.isPublic; coverUrl = list.coverUrl
                            rating = list.rating; genresText = list.genres.joinToString(", ")
                            showEditor = true
                        },
                        onDelete = { pendingDelete = list },
                        onItemClick = { item -> onItemClick(item.sourceId, item.slug) },
                        onRemoveItem = { mangaId -> viewModel.removeManga(list.id, mangaId) },
                        onClick = { onListClick(list.id) }
                    )
                }
            }
        }
    }

    pendingDelete?.let { list ->
        DeleteConfirmDialog(
            list = list,
            onConfirm = { viewModel.deleteList(list.id); pendingDelete = null },
            onDismiss = { pendingDelete = null }
        )
    }

    if (showEditor) {
        ListEditorSheet(
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

// =====================================================================================
// List card — collapsed summary row + expandable manga grid
// =====================================================================================

@Composable
private fun ListCard(
    list: CustomUserList,
    isExpanded: Boolean,
    listItems: List<CustomUserListItem>,
    onExpand: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onItemClick: (CustomUserListItem) -> Unit,
    onRemoveItem: (String) -> Unit,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (isExpanded) MangaColors.GlowPurple else MangaColors.SurfaceContainer)
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f).clickable(onClick = onClick),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(14.dp))
                ) {
                    if (list.coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(list.coverUrl).crossfade(true).build(),
                            imageLoader = LocalContext.current.imageLoader,
                            contentDescription = list.name,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MangaColors.PrimaryDim.copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(list.name.take(1), color = MangaColors.PrimaryLight, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(list.name, color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                    if (list.description.isNotBlank()) {
                        Text(
                            list.description,
                            color = MangaColors.OnSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${list.itemCount} عنصر", color = MangaColors.Cyan, style = MaterialTheme.typography.labelSmall)
                        if (list.rating > 0) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Star, contentDescription = null, tint = MangaColors.Yellow, modifier = Modifier.size(11.dp))
                                Text(String.format("%.1f", list.rating), color = MangaColors.Yellow, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        if (list.genres.isNotEmpty()) {
                            Text(
                                list.genres.take(2).joinToString(" • "),
                                color = MangaColors.Muted,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            Row {
                IconButton(onClick = onEdit, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.Edit, contentDescription = "تعديل", modifier = Modifier.size(18.dp), tint = MangaColors.Cyan)
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Filled.Delete, contentDescription = "حذف", modifier = Modifier.size(18.dp), tint = MangaColors.Error)
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onExpand)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                if (isExpanded) "إخفاء العناصر" else "عرض العناصر (${list.itemCount})",
                color = MangaColors.Cyan,
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = MangaColors.Cyan,
                modifier = Modifier.size(18.dp)
            )
        }

        AnimatedVisibility(visible = isExpanded) {
            Column {
                if (listItems.isNotEmpty()) {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.heightIn(max = 400.dp).padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(listItems, key = { it.mangaId }) { item ->
                            ListItemCard(item = item, onItemClick = { onItemClick(item) }, onRemove = { onRemoveItem(item.mangaId) })
                        }
                    }
                } else {
                    Text(
                        "القائمة فارغة — أضف مانجا من صفحة التفاصيل",
                        color = MangaColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ListItemCard(item: CustomUserListItem, onItemClick: () -> Unit, onRemove: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MangaColors.SurfaceHigh)
            .clickable(onClick = onItemClick)
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(0.72f)) {
            if (item.coverUrl.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(item.coverUrl).crossfade(true).build(),
                    imageLoader = ctx.imageLoader,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize().background(MangaColors.SurfaceContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(item.title.take(2), color = MangaColors.Primary)
                }
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f))
            ) {
                Icon(Icons.Filled.Close, contentDescription = "إزالة", modifier = Modifier.size(14.dp), tint = Color.White)
            }
        }
        Column(Modifier.padding(8.dp)) {
            Text(
                item.title, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall
            )
            if (item.rating > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, contentDescription = null, tint = MangaColors.Yellow, modifier = Modifier.size(10.dp))
                    Text(String.format("%.1f", item.rating), color = MangaColors.Yellow, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (item.genres.isNotEmpty()) {
                Text(
                    item.genres.take(2).joinToString(", "), color = MangaColors.Muted,
                    style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// =====================================================================================
// Delete confirmation
// =====================================================================================

@Composable
private fun DeleteConfirmDialog(list: CustomUserList, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(MangaColors.Background)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MangaColors.Error.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, tint = MangaColors.Error, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("حذف القائمة؟", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "سيتم حذف \"${list.name}\" وجميع عناصرها (${list.itemCount}) بشكل نهائي. لا يمكن التراجع عن هذا الإجراء.",
                color = MangaColors.OnSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f), shape = RoundedCornerShape(percent = 50)) {
                    Text("إلغاء")
                }
                Button(
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(percent = 50),
                    colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Error, contentColor = Color.White)
                ) {
                    Text("حذف")
                }
            }
        }
    }
}

// =====================================================================================
// List editor — bottom-sheet-style panel with tabs (Info / Cover / Meta / Visibility)
// =====================================================================================

@Composable
private fun ListEditorSheet(
    name: String, description: String, isPublic: Boolean,
    coverUrl: String, rating: Float, genresText: String,
    isEditing: Boolean,
    onNameChange: (String) -> Unit, onDescriptionChange: (String) -> Unit,
    onPublicChange: (Boolean) -> Unit, onCoverChange: (String) -> Unit,
    onRatingChange: (Float) -> Unit, onGenresChange: (String) -> Unit,
    onUploadCover: suspend (Uri) -> com.exapps.mangaworld.core.firebase.CloudinaryUploader.UploadResult?,
    onSave: () -> Unit, onDismiss: () -> Unit
) {
    var isUploading by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) }
    val coroutineScope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            isUploading = true
            // Upload in background
            coroutineScope.launch {
                val result = onUploadCover(it)
                if (result != null) onCoverChange(result.url)
                isUploading = false
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                    .background(MangaColors.Background)
                    .clickable(onClick = {})
            ) {
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp)
                        .align(Alignment.CenterHorizontally)
                        .size(width = 40.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MangaColors.Muted)
                )

                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (isEditing) "تعديل القائمة" else "إنشاء قائمة جديدة",
                        color = MangaColors.OnSurface,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "إغلاق", tint = MangaColors.Muted)
                    }
                }

                Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                    EditorTabRow(selectedTab = selectedTab, onTabSelected = { selectedTab = it })
                }

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    when (selectedTab) {
                        0 -> InfoTab(
                            name = name,
                            description = description,
                            onNameChange = onNameChange,
                            onDescriptionChange = onDescriptionChange
                        )
                        1 -> CoverTab(
                            coverUrl = coverUrl,
                            isUploading = isUploading,
                            onCoverChange = onCoverChange,
                            onPickImage = { launcher.launch("image/*") }
                        )
                        2 -> MetaTab(
                            rating = rating,
                            genresText = genresText,
                            onRatingChange = onRatingChange,
                            onGenresChange = onGenresChange
                        )
                        else -> VisibilityTab(isPublic = isPublic, onPublicChange = onPublicChange)
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(percent = 50)
                    ) {
                        Text("إلغاء")
                    }
                    Button(
                        onClick = onSave,
                        enabled = name.isNotBlank() && !isUploading,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(percent = 50),
                        colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Cyan, contentColor = MangaColors.Background)
                    ) {
                        Text(if (isEditing) "حفظ التغييرات" else "إنشاء القائمة", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun EditorTabRow(selectedTab: Int, onTabSelected: (Int) -> Unit) {
    val tabs = listOf("معلومات", "الغلاف", "التفاصيل", "الخصوصية")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MangaColors.SurfaceHigh)
            .padding(4.dp)
    ) {
        tabs.forEachIndexed { index, label ->
            val selected = selectedTab == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) MangaColors.SurfaceContainer else Color.Transparent)
                    .clickable(onClick = { onTabSelected(index) })
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (selected) MangaColors.Cyan else MangaColors.Muted,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
    }
}

@Composable
private fun InfoTab(
    name: String,
    description: String,
    onNameChange: (String) -> Unit,
    onDescriptionChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("اسم القائمة") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MangaColors.OnSurface, unfocusedTextColor = MangaColors.OnSurface)
        )
        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            label = { Text("الوصف") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
            maxLines = 4,
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MangaColors.OnSurface, unfocusedTextColor = MangaColors.OnSurface)
        )
    }
}

@Composable
private fun CoverTab(
    coverUrl: String,
    isUploading: Boolean,
    onCoverChange: (String) -> Unit,
    onPickImage: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MangaColors.SurfaceHigh)
                .clickable(onClick = onPickImage),
            contentAlignment = Alignment.Center
        ) {
            if (coverUrl.isNotBlank()) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = "غلاف القائمة",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center
                ) {
                    if (isUploading) {
                        CircularProgressIndicator(color = Color.White)
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("تغيير الصورة", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else if (isUploading) {
                CircularProgressIndicator(color = MangaColors.Cyan)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Image, contentDescription = null, tint = MangaColors.Muted, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("اضغط لاختيار صورة الغلاف", color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        OutlinedTextField(
            value = coverUrl,
            onValueChange = onCoverChange,
            label = { Text("أو الصق رابط صورة") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MangaColors.OnSurface, unfocusedTextColor = MangaColors.OnSurface)
        )
    }
}

@Composable
private fun MetaTab(
    rating: Float,
    genresText: String,
    onRatingChange: (Float) -> Unit,
    onGenresChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Column {
            Text("التقييم", color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(10.dp))
            val rounded = rating.roundToInt()
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 1..5) {
                    Icon(
                        imageVector = if (i <= rounded) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = null,
                        tint = MangaColors.Yellow,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Slider(
                value = rating,
                onValueChange = onRatingChange,
                valueRange = 0f..5f,
                steps = 9,
                colors = SliderDefaults.colors(
                    thumbColor = MangaColors.Yellow,
                    activeTrackColor = MangaColors.Yellow,
                    inactiveTrackColor = MangaColors.SurfaceHigh
                )
            )
            Text(String.format("%.1f / 5.0", rating), color = MangaColors.Yellow, fontWeight = FontWeight.Bold)
        }
        Column {
            OutlinedTextField(
                value = genresText,
                onValueChange = onGenresChange,
                label = { Text("التصنيفات") },
                placeholder = { Text("أكشن، مغامرة، دراما") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedTextColor = MangaColors.OnSurface, unfocusedTextColor = MangaColors.OnSurface)
            )
            Spacer(Modifier.height(4.dp))
            Text("افصل بين التصنيفات بفاصلة", color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun VisibilityTab(isPublic: Boolean, onPublicChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        VisibilityOption(
            icon = Icons.Filled.Public,
            title = "عامة",
            description = "يمكن لأي شخص العثور على هذه القائمة ومشاهدتها",
            selected = isPublic,
            onClick = { onPublicChange(true) }
        )
        VisibilityOption(
            icon = Icons.Filled.Lock,
            title = "خاصة",
            description = "أنت فقط من يمكنه رؤية هذه القائمة",
            selected = !isPublic,
            onClick = { onPublicChange(false) }
        )
    }
}

@Composable
private fun VisibilityOption(
    icon: ImageVector,
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) MangaColors.GlowCyan else MangaColors.SurfaceHigh)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = if (selected) MangaColors.Cyan else MangaColors.Muted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
            Text(description, color = MangaColors.Muted, style = MaterialTheme.typography.labelSmall)
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MangaColors.Cyan, modifier = Modifier.size(20.dp))
        }
    }
}
