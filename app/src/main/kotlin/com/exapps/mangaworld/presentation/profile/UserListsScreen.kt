package com.exapps.mangaworld.presentation.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
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
    private val communityRepository: CommunityRepository
) : ViewModel() {
    private val _selectedListId = MutableStateFlow<String?>(null)
    val lists = communityRepository.observeUserLists().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val selectedListId: StateFlow<String?> = _selectedListId.asStateFlow()
    val items = _selectedListId.flatMapLatest { id ->
        if (id == null) kotlinx.coroutines.flow.flowOf(emptyList()) else communityRepository.observeListItems(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun selectList(id: String?) { _selectedListId.value = id }
    fun saveList(listId: String?, name: String, description: String, isPublic: Boolean) {
        viewModelScope.launch {
            runCatching { communityRepository.createOrUpdateList(listId, name, description, isPublic) }
                .onSuccess { createdId -> _selectedListId.value = createdId }
        }
    }
    fun deleteList(id: String) { viewModelScope.launch { runCatching { communityRepository.deleteList(id) } } }
    fun removeManga(listId: String, mangaId: String) { viewModelScope.launch { runCatching { communityRepository.removeMangaFromList(listId, mangaId) } } }
}

@Composable
fun UserListsScreen(onBack: () -> Unit, viewModel: UserListsViewModel = hiltViewModel()) {
    val lists by viewModel.lists.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val selectedListId by viewModel.selectedListId.collectAsStateWithLifecycle()
    var showEditor by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<CustomUserList?>(null) }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var isPublic by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MangaColors.Background)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = MangaColors.OnSurface) }
            Text("قوائمي المخصصة", color = MangaColors.OnSurface, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = {
                editTarget = null; name = ""; description = ""; isPublic = false; showEditor = true
            }) { Icon(Icons.Filled.Add, null, tint = MangaColors.Cyan) }
        }

        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(lists, key = { it.id }) { list ->
                Card(colors = CardDefaults.cardColors(containerColor = if (selectedListId == list.id) MangaColors.GlowPurple else MangaColors.SurfaceContainer), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(list.name, color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)
                                if (list.description.isNotBlank()) Text(list.description, color = MangaColors.OnSurfaceVariant)
                                Text("${list.itemCount} عنصر", color = MangaColors.Cyan, style = MaterialTheme.typography.labelSmall)
                            }
                            Row {
                                IconButton(onClick = {
                                    editTarget = list
                                    name = list.name
                                    description = list.description
                                    isPublic = list.isPublic
                                    showEditor = true
                                }) { Icon(Icons.Filled.Add, null, tint = MangaColors.Cyan) }
                                IconButton(onClick = { viewModel.deleteList(list.id) }) { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) }
                            }
                        }
                        Button(onClick = { viewModel.selectList(if (selectedListId == list.id) null else list.id) }) {
                            Text(if (selectedListId == list.id) "إخفاء العناصر" else "عرض العناصر")
                        }
                        if (selectedListId == list.id) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items.forEach { item ->
                                    ListItemCard(item = item, onRemove = { viewModel.removeManga(list.id, item.mangaId) })
                                }
                                if (items.isEmpty()) Text("القائمة فارغة", color = MangaColors.OnSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showEditor) {
        AlertDialog(
            onDismissRequest = { showEditor = false },
            title = { Text(if (editTarget == null) "إنشاء قائمة" else "تعديل القائمة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("اسم القائمة") })
                    OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("الوصف") })
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = isPublic, onCheckedChange = { isPublic = it })
                        Text("قائمة عامة", color = MangaColors.OnSurfaceVariant)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.saveList(editTarget?.id, name, description, isPublic)
                    showEditor = false
                }, enabled = name.isNotBlank()) { Text("حفظ") }
            },
            dismissButton = { Button(onClick = { showEditor = false }) { Text("إلغاء") } }
        )
    }
}

@Composable
private fun ListItemCard(item: CustomUserListItem, onRemove: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MangaColors.Surface), shape = RoundedCornerShape(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.title, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold)
                Text(item.slug, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = onRemove) { Icon(Icons.Filled.Delete, null, tint = MaterialTheme.colorScheme.error) }
        }
    }
}
