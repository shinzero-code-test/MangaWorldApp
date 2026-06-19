package com.exapps.mangaworld.presentation.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.presentation.theme.MangaColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    onBack: () -> Unit,
    enabledSources: Set<String> = MangaSource.entries.map { it.id }.toSet(),
    onToggleSource: (String, Boolean) -> Unit = { _, _ -> }
) {
    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("المصادر", color = MangaColors.OnSurface) },
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            items(MangaSource.entries) { source ->
                SourceCard(
                    source = source,
                    isEnabled = source.id in enabledSources,
                    onToggle = { enabled -> onToggleSource(source.id, enabled) }
                )
            }
        }
    }
}

@Composable
private fun SourceCard(
    source: MangaSource,
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) MangaColors.Surface else MangaColors.SurfaceContainer
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    source.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    color = MangaColors.OnSurface,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    source.baseUrl.removePrefix("https://").removePrefix("http://"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MangaColors.OnSurfaceVariant
                )
                if (source.requiresVerification) {
                    Text(
                        "يتطلب التحقق",
                        style = MaterialTheme.typography.labelSmall,
                        color = MangaColors.Yellow
                    )
                }
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MangaColors.Cyan,
                    checkedTrackColor = MangaColors.Cyan.copy(alpha = 0.3f)
                )
            )
        }
    }
}
