package com.exapps.mangaworld.presentation.localstorage
import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.exapps.mangaworld.core.data.download.DownloadQueueManager
import com.exapps.mangaworld.core.data.local.entity.DownloadedMangaEntity
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject

data class ImportedChapter(
    val number: Float,
    val fileName: String,
    val pageCount: Int = 0
)

data class ImportProgress(
    val totalChapters: Int = 0,
    val processedChapters: Int = 0,
    val currentChapter: String = "",
    val isComplete: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ImportMangaViewModel @Inject constructor(
    private val downloadQueueManager: DownloadQueueManager
) : ViewModel() {

    /** Persist the imported manga to the Room database so LocalStorageScreen shows it. */
    suspend fun upsertImportedManga(entity: DownloadedMangaEntity) {
        downloadQueueManager.upsertDownloadedManga(entity)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportMangaScreen(
    onBack: () -> Unit,
    onImportComplete: () -> Unit,
    viewModel: ImportMangaViewModel = hiltViewModel()
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var mangaName by remember { mutableStateOf("") }
    var mangaDescription by remember { mutableStateOf("") }
    var mangaGenres by remember { mutableStateOf("") }
    var coverUri by remember { mutableStateOf<Uri?>(null) }
    var folderUri by remember { mutableStateOf<Uri?>(null) }
    var folderName by remember { mutableStateOf("") }
    var chapters by remember { mutableStateOf<List<ImportedChapter>>(emptyList()) }
    var importProgress by remember { mutableStateOf<ImportProgress?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        coverUri = uri
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            folderUri = it
            folderName = getFolderDisplayName(context, it)
            scope.launch {
                chapters = scanFolderForChapters(context, it)
            }
        }
    }

    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_external_manga), color = MangaColors.OnSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, stringResource(R.string.back), tint = MangaColors.OnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MangaColors.Surface)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            // Folder selection
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MangaColors.Surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(stringResource(R.string.manga_folder), style = MaterialTheme.typography.titleMedium,
                            color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.choose_manga_folder_desc),
                            style = MaterialTheme.typography.bodySmall, color = MangaColors.OnSurfaceVariant)

                        OutlinedButton(
                            onClick = { folderPicker.launch(null) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (folderName.isNotBlank()) folderName else stringResource(R.string.choose_folder))
                        }

                        if (chapters.isNotEmpty()) {
                            Text(stringResource(R.string.fmt_069, chapters.size),
                                style = MaterialTheme.typography.bodySmall, color = MangaColors.Cyan)
                            LazyColumn(
                                modifier = Modifier.heightIn(max = 200.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(chapters) { chapter ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MangaColors.SurfaceContainer, RoundedCornerShape(8.dp))
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(Icons.Filled.Article, null,
                                            modifier = Modifier.size(16.dp), tint = MangaColors.Primary)
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.fmt_055, chapter.number.toInt(), chapter.fileName),
                                            style = MaterialTheme.typography.bodySmall, color = MangaColors.OnSurface)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Cover selection
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MangaColors.Surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(stringResource(R.string.manga_cover), style = MaterialTheme.typography.titleMedium,
                            color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)

                        OutlinedButton(
                            onClick = { coverPicker.launch("image/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Image, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.choose_cover_image))
                        }

                        coverUri?.let { uri ->
                            AsyncImage(
                                model = uri,
                                contentDescription = stringResource(R.string.cover),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(200.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }

            // Manga metadata
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MangaColors.Surface),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(stringResource(R.string.manga_info), style = MaterialTheme.typography.titleMedium,
                            color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)

                        OutlinedTextField(
                            value = mangaName,
                            onValueChange = { mangaName = it },
                            label = { Text(stringResource(R.string.manga_name_required)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = mangaDescription,
                            onValueChange = { mangaDescription = it },
                            label = { Text(stringResource(R.string.description)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3
                        )

                        OutlinedTextField(
                            value = mangaGenres,
                            onValueChange = { mangaGenres = it },
                            label = { Text(stringResource(R.string.str_140)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                }
            }

            // Import progress
            importProgress?.let { progress ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MangaColors.Surface),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (progress.error != null) {
                                Text(stringResource(R.string.fmt_073, progress.error), color = MangaColors.Error,
                                    style = MaterialTheme.typography.bodySmall)
                            } else if (progress.isComplete) {
                                Text(stringResource(R.string.str_239), color = MangaColors.Cyan,
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        onImportComplete()
                                        onBack()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.ok))
                                }
                            } else {
                                LinearProgressIndicator(
                                    progress = {
                                        if (progress.totalChapters > 0)
                                            progress.processedChapters.toFloat() / progress.totalChapters
                                        else 0f
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(stringResource(R.string.fmt_072, progress.processedChapters, progress.totalChapters),
                                    style = MaterialTheme.typography.bodySmall, color = MangaColors.OnSurfaceVariant)
                                if (progress.currentChapter.isNotBlank()) {
                                    Text(stringResource(R.string.fmt_060, progress.currentChapter),
                                        style = MaterialTheme.typography.labelSmall, color = MangaColors.Muted)
                                }
                            }
                        }
                    }
                }
            }

            // Import button
            item {
                Button(
                    onClick = {
                        if (mangaName.isBlank()) {
                            Toast.makeText(context, context.getString(R.string.enter_manga_name), Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (folderUri == null) {
                            Toast.makeText(context, context.getString(R.string.choose_manga_folder), Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (chapters.isEmpty()) {
                            Toast.makeText(context, context.getString(R.string.no_chapters_found), Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isProcessing = true
                        scope.launch {
                            val entity = importManga(
                                context = context,
                                folderUri = folderUri!!,
                                coverUri = coverUri,
                                mangaName = mangaName,
                                description = mangaDescription,
                                genres = mangaGenres.split(",").map { it.trim() }.filter { it.isNotBlank() },
                                chapters = chapters,
                                onProgress = { importProgress = it }
                            )
                            // Persist to Room DB so LocalStorageScreen can display it
                            if (entity != null) {
                                viewModel.upsertImportedManga(entity)
                            }
                            isProcessing = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isProcessing && mangaName.isNotBlank() && folderUri != null && chapters.isNotEmpty()
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MangaColors.OnSurface
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.import_manga))
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

private fun getFolderDisplayName(context: Context, uri: Uri): String {
    return try {
        val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
        doc?.name?.takeIf { it.isNotBlank() } ?: run {
            val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(uri)
            treeDocId.substringAfterLast('/').takeIf { it.isNotBlank() } ?: context.getString(R.string.folder)
        }
    } catch (_: Exception) {
        context.getString(R.string.folder)
    }
}

/**
 * Fallback: parse the display name from the tree document ID embedded in the URI.
 * Tree URI format: content://.../tree/primary%3ADownload%2FOmniFetch
 * DocumentsContract.getTreeDocumentId() decodes this to "primary:Download/OmniFetch"
 * → we take the last path segment.
 */
@Composable
private fun extractDisplayNameFromTreeId(uri: Uri): String {
    val context = LocalContext.current
    return try {
        val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(uri)
        treeDocId.substringAfterLast('/').takeIf { it.isNotBlank() } ?: context.getString(R.string.folder)
    } catch (_: Exception) {
        context.getString(R.string.folder)
    }
}

private suspend fun scanFolderForChapters(context: Context, folderUri: Uri): List<ImportedChapter> {
    return withContext(Dispatchers.IO) {
        val chapters = mutableListOf<ImportedChapter>()

        // Primary path: DocumentFile.fromTreeUri() is the correct SAF API for Tree URIs
        val doc = try {
            androidx.documentfile.provider.DocumentFile.fromTreeUri(context, folderUri)
        } catch (_: Exception) {
            null
        }

        if (doc == null || !doc.exists() || !doc.isDirectory) {
            // Cannot resolve the tree — return empty (user will see "0 chapters")
            return@withContext chapters
        }

        doc.listFiles()
            .filter { file ->
                file.isFile && file.name?.let { name ->
                    name.endsWith(".zip", true) ||
                        name.endsWith(".cbz", true) ||
                        name.endsWith(".rar", true)
                } == true
            }
            .sortedBy { file ->
                file.name?.replace("[^0-9.]".toRegex(), "")?.toFloatOrNull() ?: 0f
            }
            .forEach { file ->
                val name = file.name ?: "unknown"
                val chapterNumber = name.replace("[^0-9.]".toRegex(), "").toFloatOrNull()
                    ?: (chapters.size + 1).toFloat()
                chapters.add(ImportedChapter(number = chapterNumber, fileName = name))
            }

        chapters
    }
}

private suspend fun importManga(
    context: Context,
    folderUri: Uri,
    coverUri: Uri?,
    mangaName: String,
    description: String,
    genres: List<String>,
    chapters: List<ImportedChapter>,
    onProgress: (ImportProgress) -> Unit
): DownloadedMangaEntity? {
    return withContext(Dispatchers.IO) {
        try {
            val downloadsDir = File(context.getExternalFilesDir(null), "downloads")
            val mangaId = "imported_${mangaName.replace("[^a-zA-Z0-9]".toRegex(), "_").lowercase()}"
            val mangaDir = File(downloadsDir, mangaId)
            mangaDir.mkdirs()

            // Save cover image
            coverUri?.let { uri ->
                val coverFile = File(mangaDir, "cover.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(coverFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            val doc = try {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(context, folderUri)
            } catch (_: Exception) { null }

            if (doc == null || !doc.exists() || !doc.isDirectory) {
                throw IllegalStateException(
                    context.getString(R.string.str_228)
                )
            }

            var processedCount = 0

            // DocumentFile.fromTreeUri() + listFiles() is the correct way to enumerate
            // children from a Tree URI. Calling contentResolver.query() on a Tree URI
            // throws UnsupportedOperationException — never use it as a fallback.
            val files = doc.listFiles().filter { file ->
                file.isFile && file.name?.let { name ->
                    name.endsWith(".zip", true) || name.endsWith(".cbz", true) || name.endsWith(".rar", true)
                } == true
            }

            files.sortedBy { file ->
                file.name?.replace("[^0-9.]".toRegex(), "")?.toFloatOrNull() ?: 0f
            }.forEach { archiveFile ->
                processedCount++
                val chapterName = archiveFile.name?.substringBeforeLast('.') ?: "chapter_$processedCount"
                val chapterDir = File(mangaDir, chapterName)
                chapterDir.mkdirs()

                onProgress(ImportProgress(
                    totalChapters = chapters.size,
                    processedChapters = processedCount,
                    currentChapter = chapterName
                ))

                // Extract zip/cbz using DocumentFile URI
                try {
                    val inputStream = context.contentResolver.openInputStream(archiveFile.uri)
                    if (inputStream != null) {
                        ZipInputStream(inputStream).use { zip ->
                            var entry = zip.nextEntry
                            var pageCount = 0
                            while (entry != null) {
                                if (!entry.isDirectory) {
                                    val ext = entry.name?.substringAfterLast('.', "")?.lowercase() ?: ""
                                    if (ext in setOf("jpg", "jpeg", "png", "webp")) {
                                        pageCount++
                                        val outputFile = File(chapterDir, "%03d.%s".format(pageCount, ext))
                                        FileOutputStream(outputFile).use { out ->
                                            zip.copyTo(out)
                                        }
                                    }
                                }
                                entry = zip.nextEntry
                            }
                            // Mark chapter as complete
                            File(chapterDir, ".completed").createNewFile()
                        }
                    }
                } catch (_: Exception) {
                    // Skip files that aren't valid zip or can't be read
                }
            }

            // Create manga metadata JSON
            val metadata = JSONObject().apply {
                put("id", mangaId)
                put("title", mangaName)
                put("description", description)
                put("genres", JSONArray(genres))
                put("sourceId", "imported")
                put("importedAt", System.currentTimeMillis())
                put("totalChapters", chapters.size)
                put("isImported", true)
            }
            File(mangaDir, "metadata.json").writeText(metadata.toString())

            onProgress(ImportProgress(
                totalChapters = chapters.size,
                processedChapters = chapters.size,
                isComplete = true
            ))

            // Return entity so caller can persist it to Room database
            val coverPath = File(mangaDir, "cover.jpg")
            DownloadedMangaEntity(
                mangaId = mangaId,
                slug = mangaId,
                title = mangaName,
                coverUrl = "",
                localCoverPath = coverPath.absolutePath.takeIf { coverPath.exists() },
                sourceId = "imported",
                totalChapters = chapters.size,
                downloadedChapters = chapters.size,
                genresJson = JSONArray(genres).toString(),
                description = description
            )
        } catch (e: Exception) {
            onProgress(ImportProgress(error = e.message ?: context.getString(R.string.unknown_error)))
            null
        }
    }
}
