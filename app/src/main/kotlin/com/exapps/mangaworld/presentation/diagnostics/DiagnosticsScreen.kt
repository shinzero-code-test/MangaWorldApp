package com.exapps.mangaworld.presentation.diagnostics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
import javax.inject.Inject

data class SourceDiagnosticStatus(
    val source: MangaSource,
    val homeOk: Boolean,
    val searchResults: Int,
    val hasCookie: Boolean,
    val error: String? = null
)

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
                                hasCookie = settingsRepository.getCookies(source.baseUrl.removePrefix("https://").removePrefix("http://")).first()?.isNotBlank() == true,
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
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع", tint = MangaColors.OnSurface)
            }
            Text("التشخيص والصحة", style = MaterialTheme.typography.titleLarge, color = MangaColors.OnSurface, fontWeight = FontWeight.Bold)
            IconButton(onClick = viewModel::refresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "تحديث", tint = MangaColors.OnSurface)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (state.isLoading) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(color = MangaColors.Primary)
            }
        }

        DiagnosticsSectionCard(title = "الحالة العامة") {
            DiagnosticLine("المصادر المفعلة", state.appSettings.enabledSources.size.toString())
            DiagnosticLine("الكلمات المحجوبة", state.appSettings.contentBlacklist.size.toString())
            DiagnosticLine("حجم كاش الصور", formatDiagnosticBytes(state.imageCacheSizeBytes))
            DiagnosticLine("آخر تحديث لويدجت", if (state.widgetSnapshotUpdatedAt > 0) java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(state.widgetSnapshotUpdatedAt)) else "لا يوجد")
            DiagnosticLine("القفل البيومتري", if (state.appSettings.biometricLockEnabled) "مفعل" else "معطل")
        }

        Spacer(Modifier.height(12.dp))
        DiagnosticsSectionCard(title = "صحة المصادر") {
            state.sources.forEachIndexed { index, source ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Text(source.source.displayName, color = MangaColors.OnSurface, fontWeight = FontWeight.SemiBold)
                    DiagnosticLine("الصفحة الرئيسية", if (source.homeOk) "سليم" else "فشل")
                    DiagnosticLine("نتائج البحث (solo)", source.searchResults.toString())
                    DiagnosticLine("كوكي Cloudflare", if (source.hasCookie) "متوفر" else "غير متوفر")
                    if (!source.error.isNullOrBlank()) {
                        Text(source.error, color = MangaColors.Muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (index < state.sources.lastIndex) Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun DiagnosticsSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MangaColors.SurfaceContainer)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, color = MangaColors.Cyan, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun DiagnosticLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MangaColors.OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, color = MangaColors.OnSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
    Spacer(Modifier.height(4.dp))
}
