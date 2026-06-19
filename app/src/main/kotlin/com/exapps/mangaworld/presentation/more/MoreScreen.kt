package com.exapps.mangaworld.presentation.more

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.exapps.mangaworld.presentation.theme.MangaColors

data class MoreMenuItem(
    val icon: ImageVector,
    val title: String,
    val subtitle: String? = null,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    onOpenDownloads: () -> Unit,
    onOpenLocalStorage: () -> Unit,
    onOpenReadingStats: () -> Unit,
    onOpenCollections: () -> Unit,
    onOpenGoals: () -> Unit,
    onOpenSources: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenCloudSync: () -> Unit
) {
    Scaffold(
        containerColor = MangaColors.Background,
        topBar = {
            TopAppBar(
                title = { Text("المزيد", color = MangaColors.OnSurface) },
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
            item {
                Text(
                    "المحتوى",
                    style = MaterialTheme.typography.labelLarge,
                    color = MangaColors.Cyan,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
            }
            item {
                MoreMenuItem(
                    icon = Icons.Filled.Download,
                    title = "التنزيلات",
                    subtitle = "الفصول المنزّلة",
                    onClick = onOpenDownloads
                )
            }
            item {
                MoreMenuItem(
                    icon = Icons.Filled.FolderOpen,
                    title = "المحلي",
                    subtitle = "المانجا المحفوظة محلياً",
                    onClick = onOpenLocalStorage
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "الإحصائيات",
                    style = MaterialTheme.typography.labelLarge,
                    color = MangaColors.Cyan,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
            }
            item {
                MoreMenuItem(
                    icon = Icons.Filled.BarChart,
                    title = "إحصائيات القراءة",
                    subtitle = "وقت القراءة والإنجازات",
                    onClick = onOpenReadingStats
                )
            }
            item {
                MoreMenuItem(
                    icon = Icons.Filled.EmojiEvents,
                    title = "الأهداف والإنجازات",
                    subtitle = "تتبع تقدمك",
                    onClick = onOpenGoals
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "الأدوات",
                    style = MaterialTheme.typography.labelLarge,
                    color = MangaColors.Cyan,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
            }
            item {
                MoreMenuItem(
                    icon = Icons.Filled.List,
                    title = "القوائم المخصصة",
                    subtitle = "تنظيم المانجا في قوائم",
                    onClick = onOpenCollections
                )
            }
            item {
                MoreMenuItem(
                    icon = Icons.Filled.Cloud,
                    title = "المزامنة السحابية",
                    subtitle = "مزامنة البيانات مع السحابة",
                    onClick = onOpenCloudSync
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "الإعدادات",
                    style = MaterialTheme.typography.labelLarge,
                    color = MangaColors.Cyan,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                )
            }
            item {
                MoreMenuItem(
                    icon = Icons.Filled.Settings,
                    title = "الإعدادات",
                    subtitle = "تخصيص التطبيق",
                    onClick = onOpenSettings
                )
            }
            item {
                MoreMenuItem(
                    icon = Icons.Filled.BugReport,
                    title = "التشخيص",
                    subtitle = "معلومات تقنية",
                    onClick = onOpenDiagnostics
                )
            }
        }
    }
}

@Composable
private fun MoreMenuItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
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
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MangaColors.Cyan,
                modifier = Modifier.size(24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MangaColors.OnSurface,
                    fontWeight = FontWeight.Medium
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MangaColors.OnSurfaceVariant
                    )
                }
            }
            Icon(
                Icons.Filled.ChevronLeft,
                contentDescription = null,
                tint = MangaColors.Muted
            )
        }
    }
}
