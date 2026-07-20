
package com.exapps.mangaworld.presentation.collections
import androidx.compose.ui.res.stringResource
import com.exapps.mangaworld.R



@HiltViewModel
class CollectionsViewModel @Inject constructor(
    private val collectionManager: CollectionManager
) : ViewModel() {

    val collections = collectionManager.collections
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createCollection(name: String, description: String) {
        viewModelScope.launch {
            collectionManager.createCollection(name, description)
        }
    }

    fun deleteCollection(id: String) {
        viewModelScope.launch {
            collectionManager.deleteCollection(id)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    onBack: () -> Unit,
    onCollectionClick: (String) -> Unit,
    viewModel: CollectionsViewModel = hiltViewModel()
) {
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.library_custom_lists), color = MangaColors.OnSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.back), tint = MangaColors.OnSurface)
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateDialog = true }) {
                        Icon(Icons.Filled.Add, stringResource(R.string.add), tint = MangaColors.Cyan)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MangaColors.Surface)
            )
        }
    ) { padding ->
        if (collections.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        stringResource(R.string.library_empty_lists),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MangaColors.OnSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showCreateDialog = true }) {
                        Text(stringResource(R.string.create_new_list))
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                items(collections) { collection ->
                    CollectionCard(
                        collection = collection,
                        onClick = { onCollectionClick(collection.id) },
                        onDelete = { viewModel.deleteCollection(collection.id) }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateCollectionDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { name, description ->
                viewModel.createCollection(name, description)
                showCreateDialog = false
            }
        )
    }
}

@Composable
private fun CollectionCard(
    collection: MangaCollection,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MangaColors.Surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    collection.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MangaColors.OnSurface,
                    fontWeight = FontWeight.Bold
                )
                if (collection.description.isNotBlank()) {
                    Text(
                        collection.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MangaColors.OnSurfaceVariant
                    )
                }
                Text(
                    stringResource(R.string.fmt_034, collection.mangaIds.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MangaColors.Cyan
                )
            }
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(Icons.Filled.Delete, stringResource(R.string.delete), tint = MangaColors.Error)
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text(stringResource(R.string.delete_list)) },
            text = { Text(stringResource(R.string.str_436, collection.name) + "؟") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete()
                    showDeleteConfirm = false
                }) {
                    Text(stringResource(R.string.delete), color = MangaColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun CreateCollectionDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.create_new_list)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.list_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.str_198)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, description) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

