package com.exapps.mangaworld.presentation.localstorage

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.exapps.mangaworld.presentation.theme.MangaColors
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportMangaScreen(
    onBack: () -> Unit,
    onImportComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
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
                title = { Text("استيراد مانجا خارجية", color = MangaColors.OnSurface) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "رجوع", tint = MangaColors.OnSurface)
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
                        Text("مجلد المانجا", style = MaterialTheme.typography.titleMedium,
                            color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)
                        Text("اختر المجلد الذي يحتوي على فصول المانجا (ZIP/CBZ/RAR)",
                            style = MaterialTheme.typography.bodySmall, color = MangaColors.OnSurfaceVariant)

                        OutlinedButton(
                            onClick = { folderPicker.launch(null) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.FolderOpen, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (folderName.isNotBlank()) folderName else "اختر المجلد")
                        }

                        if (chapters.isNotEmpty()) {
                            Text("تم العثور على ${chapters.size} فصل",
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
                                        Text("الفصل ${chapter.number.toInt()} - ${chapter.fileName}",
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
                        Text("غلاف المانجا", style = MaterialTheme.typography.titleMedium,
                            color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)

                        OutlinedButton(
                            onClick = { coverPicker.launch("image/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Image, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("اختر صورة الغلاف")
                        }

                        coverUri?.let { uri ->
                            AsyncImage(
                                model = uri,
                                contentDescription = "الغلاف",
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
                        Text("معلومات المانجا", style = MaterialTheme.typography.titleMedium,
                            color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)

                        OutlinedTextField(
                            value = mangaName,
                            onValueChange = { mangaName = it },
                            label = { Text("اسم المانجا *") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = mangaDescription,
                            onValueChange = { mangaDescription = it },
                            label = { Text("الوصف") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3
                        )

                        OutlinedTextField(
                            value = mangaGenres,
                            onValueChange = { mangaGenres = it },
                            label = { Text("التصنيفات (مفصولة بفاصلة)") },
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
                                Text("خطأ: ${progress.error}", color = MangaColors.Error,
                                    style = MaterialTheme.typography.bodySmall)
                            } else if (progress.isComplete) {
                                Text("تم الاستيراد بنجاح!", color = MangaColors.Cyan,
                                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        onImportComplete()
                                        onBack()
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("تم")
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
                                Text("جاري استيراد الفصول: ${progress.processedChapters} / ${progress.totalChapters}",
                                    style = MaterialTheme.typography.bodySmall, color = MangaColors.OnSurfaceVariant)
                                if (progress.currentChapter.isNotBlank()) {
                                    Text("الفصل الحالي: ${progress.currentChapter}",
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
                            Toast.makeText(context, "أدخل اسم المانجا", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (folderUri == null) {
                            Toast.makeText(context, "اختر مجلد المانجا", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (chapters.isEmpty()) {
                            Toast.makeText(context, "لم يتم العثور على فصول", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        isProcessing = true
                        scope.launch {
                            val result = importManga(
                                context = context,
                                folderUri = folderUri!!,
                                coverUri = coverUri,
                                mangaName = mangaName,
                                description = mangaDescription,
                                genres = mangaGenres.split(",").map { it.trim() }.filter { it.isNotBlank() },
                                chapters = chapters,
                                onProgress = { progress = it }
                            )
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
                    Text("استيراد المانجا")
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

private fun getFolderDisplayName(context: Context, uri: Uri): String {
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    return cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) it.getString(nameIndex) ?: "مجلد" else "مجلد"
        } else "مجلد"
    } ?: "مجلد"
}

private suspend fun scanFolderForChapters(context: Context, folderUri: Uri): List<ImportedChapter> {
    return withContext(Dispatchers.IO) {
        val chapters = mutableListOf<ImportedChapter>()
        val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext emptyList()

        doc.listFiles().filter { file ->
            file.isFile && file.name?.let { name ->
                name.endsWith(".zip", true) || name.endsWith(".cbz", true) || name.endsWith(".rar", true)
            } == true
        }.sortedBy { file ->
            file.name?.replace("[^0-9.]".toRegex(), "")?.toFloatOrNull() ?: 0f
        }.forEach { file ->
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
): Boolean {
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

            val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, folderUri) ?: return@withContext false
            var processedCount = 0

            doc.listFiles().filter { file ->
                file.isFile && file.name?.let { name ->
                    name.endsWith(".zip", true) || name.endsWith(".cbz", true) || name.endsWith(".rar", true)
                } == true
            }.sortedBy { file ->
                file.name?.replace("[^0-9.]".toRegex(), "")?.toFloatOrNull() ?: 0f
            }.forEach { archiveFile ->
                processedCount++
                val chapterName = archiveFile.nameWithoutExtension ?: "chapter_$processedCount"
                val chapterDir = File(mangaDir, chapterName)
                chapterDir.mkdirs()

                onProgress(ImportProgress(
                    totalChapters = chapters.size,
                    processedChapters = processedCount,
                    currentChapter = chapterName
                ))

                // Extract zip/cbz
                try {
                    context.contentResolver.openInputStream(archiveFile.uri)?.use { inputStream ->
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
                    // Skip files that aren't valid zip
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
            true
        } catch (e: Exception) {
            onProgress(ImportProgress(error = e.message ?: "خطأ غير معروف"))
            false
        }
    }
}
