package com.exapps.mangaworld.presentation.diagnostics
import com.exapps.mangaworld.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.exapps.mangaworld.core.data.CacheManager
import com.exapps.mangaworld.core.data.WidgetSnapshotStore
import com.exapps.mangaworld.core.data.remote.scraper.MangaScraper
import com.exapps.mangaworld.domain.model.AppSettings
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.domain.model.effectiveHost
import com.exapps.mangaworld.domain.repository.SettingsRepository
import com.exapps.mangaworld.presentation.theme.MangaColors
import com.exapps.mangaworld.presentation.utils.formatDiagnosticBytes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import androidx.compose.runtime.Stable
import javax.inject.Inject

// =====================================================================================
// Data model & ViewModel — business logic is unchanged from the original implementation.
// =====================================================================================

data class SourceDiagnosticStatus(
    val source: MangaSource,
    val homeOk: Boolean,
    val searchResults: Int,
    val hasCookie: Boolean,
    val error: String? = null
)

@Stable
data class DiagnosticsUiState(
    val isLoading: Boolean = true,
    val appSettings: AppSettings = AppSettings(),
    val imageCacheSizeBytes: Long = 0L,
    val widgetSnapshotUpdatedAt: Long = 0L,
    val sources: List<SourceDiagnosticStatus> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val scrapers: Map<String, @JvmSuppressWildcards MangaScraper>,
    private val settingsRepository: SettingsRepository,
    private val widgetSnapshotStore: WidgetSnapshotStore,
    private val cacheManager: CacheManager
) : ViewModel() {
    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            val settings = settingsRepository.getAppSettings().first()
            val widgetUpdated = widgetSnapshotStore.lastUpdatedAt()
            val cacheSize = cacheManager.getImageCacheSizeBytes()
            val statuses = coroutineScope {
                MangaSource.entries.map { source ->
                    async {
                        val scraper = scrapers[source.id]
                        if (scraper == null) {
                            SourceDiagnosticStatus(source, homeOk = false, searchResults = 0, hasCookie = false, error = "Scraper missing")
                        } else {
                            val home = scraper.getHomeData()
                            val search = scraper.searchManga("solo", 1)
                            SourceDiagnosticStatus(
                                source = source,
                                homeOk = home.isSuccess,
                                searchResults = search.getOrDefault(emptyList()).size,
                                hasCookie = settingsRepository.getCookies(source.effectiveHost()).first()?.isNotBlank() == true,
                                error = home.exceptionOrNull()?.message ?: search.exceptionOrNull()?.message
                            )
                        }
                    }
                }.awaitAll()
            }
            _state.value = DiagnosticsUiState(
                isLoading = false,
                appSettings = settings,
                imageCacheSizeBytes = cacheSize,
                widgetSnapshotUpdatedAt = widgetUpdated,
                sources = statuses.sortedBy { it.source.displayName }
            )
        }
    }
}

// =====================================================================================
// Screen
// =====================================================================================

@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MangaColors.Background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), tint = MangaColors.Cyan)
            }
            Text(
                stringResource(R.string.diagnostics_health),
                style = MaterialTheme.typography.titleLarge,
                color = MangaColors.OnSurface,
                fontWeight = FontWeight.Bold
            )
            IconButton(
                onClick = viewModel::refresh,
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MangaColors.SurfaceContainer)
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.update), tint = MangaColors.Cyan)
            }
        }

        Spacer(Modifier.height(16.dp))

        if (state.isLoading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MangaColors.Cyan)
            }
        }

        GeneralStatusCard(
            appSettings = state.appSettings,
            imageCacheSizeBytes = state.imageCacheSizeBytes,
            widgetSnapshotUpdatedAt = state.widgetSnapshotUpdatedAt
        )

        Spacer(Modifier.height(24.dp))

        SourcesSectionHeader()

        Spacer(Modifier.height(14.dp))

        state.sources.forEachIndexed { index, source ->
            SourceHealthCard(status = source)
            if (index < state.sources.lastIndex) {
                Spacer(Modifier.height(12.dp))
            }
        }

        Spacer(Modifier.height(20.dp))

        InfoFooterBanner()

        Spacer(Modifier.height(12.dp))
    }
}

// =====================================================================================
// General status card
// =====================================================================================

@Composable
private fun GeneralStatusCard(
    appSettings: AppSettings,
    imageCacheSizeBytes: Long,
    widgetSnapshotUpdatedAt: Long
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MangaColors.SurfaceContainer)
            .padding(18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HeroPulseOrb()
            Spacer(Modifier.width(16.dp))
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.general_status),
                    color = MangaColors.OnSurface,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MangaColors.GlowCyan),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.VerifiedUser, contentDescription = null, tint = MangaColors.Cyan, modifier = Modifier.size(18.dp))
                }
            }
        }

        Spacer(Modifier.height(18.dp))

        val rows = listOf(
            Triple(Icons.Filled.Storage, MangaColors.Cyan, stringResource(R.string.enabled_sources)) to appSettings.enabledSources.size.toString(),
            Triple(Icons.Filled.Block, MangaColors.Pink, stringResource(R.string.blocked_keywords)) to appSettings.contentBlacklist.size.toString(),
            Triple(Icons.Filled.Image, MangaColors.Orange, stringResource(R.string.str_255)) to formatDiagnosticBytes(imageCacheSizeBytes),
            Triple(Icons.Filled.AccessTime, MangaColors.PrimaryLight, stringResource(R.string.str_009)) to lastUpdatedLabel(widgetSnapshotUpdatedAt),
            Triple(Icons.Filled.Fingerprint, MangaColors.Green, stringResource(R.string.settings_biometric)) to if (appSettings.biometricLockEnabled) stringResource(R.string.enabled) else stringResource(R.string.disabled)
        )

        rows.forEachIndexed { index, (meta, value) ->
            val (icon, tint, label) = meta
            StatRow(icon = icon, tint = tint, label = label, value = value)
            if (index < rows.lastIndex) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .padding(vertical = 10.dp)
                        .background(MangaColors.OnSurface.copy(alpha = 0.06f))
                )
            }
        }
    }
}

@Composable
private fun lastUpdatedLabel(updatedAt: Long): String {
    return if (updatedAt > 0) {
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(updatedAt))
    } else {
        stringResource(R.string.none_alt)
    }
}

@Composable
private fun HeroPulseOrb() {
    Box(
        modifier = Modifier
            .size(84.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        MangaColors.PrimaryLight.copy(alpha = 0.55f),
                        MangaColors.Cyan.copy(alpha = 0.25f),
                        MangaColors.Background
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Filled.Favorite,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.9f),
            modifier = Modifier.size(30.dp)
        )
    }
}

@Composable
private fun StatRow(icon: ImageVector, tint: Color, label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            value,
            color = MangaColors.OnSurface,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyLarge
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
            }
        }
    }
}

// =====================================================================================
// Sources section header — decorative divider with a centered label
// =====================================================================================

@Composable
private fun SourcesSectionHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(MangaColors.OnSurface.copy(alpha = 0.12f))
        )
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.sources_health),
            color = MangaColors.OnSurface,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium
        )
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Filled.Timeline, contentDescription = null, tint = MangaColors.Cyan, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(MangaColors.OnSurface.copy(alpha = 0.12f))
        )
    }
}

// =====================================================================================
// Per-source health card
// =====================================================================================

@Composable
private fun SourceHealthCard(status: SourceDiagnosticStatus) {
    val brandColors = remember {
        listOf(MangaColors.PrimaryLight, MangaColors.Cyan, MangaColors.Pink, MangaColors.Orange, MangaColors.Green)
    }
    val brandColor = remember(status.source.id) {
        brandColors[status.source.id.hashCode().and(0x7FFFFFFF) % brandColors.size]
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(MangaColors.SurfaceContainer)
            .padding(16.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            // Value column
            Column(
                modifier = Modifier.width(78.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    if (status.homeOk) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
                    contentDescription = null,
                    tint = if (status.homeOk) MangaColors.Green else MangaColors.Error,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (status.homeOk) stringResource(R.string.ok_alt) else stringResource(R.string.str_331),
                    color = if (status.homeOk) MangaColors.Green else MangaColors.Error,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    status.searchResults.toString(),
                    color = MangaColors.OnSurface,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (status.hasCookie) stringResource(R.string.available) else stringResource(R.string.settings_unavailable),
                    color = if (status.hasCookie) MangaColors.Green else MangaColors.Muted,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            Spacer(Modifier.width(12.dp))

            // Label column
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    status.source.displayName,
                    color = MangaColors.OnSurface,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(10.dp))
                SourceDetailLabel(icon = Icons.Filled.Home, label = stringResource(R.string.home_page))
                Spacer(Modifier.height(8.dp))
                SourceDetailLabel(icon = Icons.Filled.Search, label = stringResource(R.string.str_426))
                Spacer(Modifier.height(8.dp))
                SourceDetailLabel(icon = Icons.Filled.Public, label = stringResource(R.string.str_355))
            }

            Spacer(Modifier.width(10.dp))

            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(brandColor),
                contentAlignment = Alignment.Center
            ) {
                if (status.source.logoRes != 0) {
                    androidx.compose.foundation.Image(
                        painter = painterResource(status.source.logoRes),
                        contentDescription = status.source.displayName,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        status.source.displayName.take(1).uppercase(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        if (!status.error.isNullOrBlank()) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MangaColors.Error.copy(alpha = 0.12f))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MangaColors.Error, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    status.error,
                    color = MangaColors.Error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SourceDetailLabel(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MangaColors.Muted, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    }
}

// =====================================================================================
// Footer banner
// =====================================================================================

@Composable
private fun InfoFooterBanner() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MangaColors.SurfaceContainer)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            stringResource(R.string.str_211),
            color = MangaColors.OnSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(10.dp))
        Icon(Icons.Filled.Info, contentDescription = null, tint = MangaColors.Cyan, modifier = Modifier.size(20.dp))
    }
}
