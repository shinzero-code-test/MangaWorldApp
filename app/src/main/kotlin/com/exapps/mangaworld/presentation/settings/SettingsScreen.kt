package com.exapps.mangaworld.presentation.settings

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.exapps.mangaworld.core.data.CookieCache
import com.exapps.mangaworld.domain.model.*
import com.exapps.mangaworld.presentation.webview.WebViewSolverActivity
import com.exapps.mangaworld.presentation.components.GradientDivider
import com.exapps.mangaworld.presentation.theme.MangaColors

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val app by viewModel.appSettings.collectAsStateWithLifecycle()
    val reader by viewModel.readerSettings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cfLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val cookies = result.data?.getStringExtra(WebViewSolverActivity.RESULT_COOKIES).orEmpty()
            val domain = result.data?.getStringExtra(WebViewSolverActivity.EXTRA_DOMAIN).orEmpty()
            if (cookies.isNotBlank() && domain.isNotBlank()) {
                CookieCache.put(domain, cookies)
                viewModel.saveCookies(domain, cookies)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(MangaColors.Background)
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Text(
            "الإعدادات", style = MaterialTheme.typography.headlineMedium,
            color = MangaColors.OnSurface, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        )

        // ── Appearance ────────────────────────────────────────────────────────
        SettingsSection("المظهر") {
            SettingsItem(
                icon = Icons.Filled.Palette, title = "السمة",
                subtitle = app.theme.label
            ) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { expanded = true }) {
                        Text(app.theme.label, color = MangaColors.Cyan)
                        Icon(Icons.Filled.ArrowDropDown, null, tint = MangaColors.Cyan)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                        modifier = Modifier.background(MangaColors.SurfaceContainer)) {
                        AppTheme.values().forEach { theme ->
                            DropdownMenuItem(
                                text = { Text(theme.label, color = MangaColors.OnSurface) },
                                onClick = { viewModel.setTheme(theme); expanded = false },
                                leadingIcon = {
                                    if (theme == app.theme)
                                        Icon(Icons.Filled.Check, null, tint = MangaColors.Primary)
                                }
                            )
                        }
                    }
                }
            }
        }

        // ── Reader ────────────────────────────────────────────────────────────
        SettingsSection("القارئ") {
            SettingsItem(icon = Icons.Filled.ChromeReaderMode, title = "وضع القراءة",
                subtitle = reader.mode.label) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { expanded = true }) {
                        Text(reader.mode.label, color = MangaColors.Cyan)
                        Icon(Icons.Filled.ArrowDropDown, null, tint = MangaColors.Cyan)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false },
                        modifier = Modifier.background(MangaColors.SurfaceContainer)) {
                        ReaderMode.values().forEach { m ->
                            DropdownMenuItem(
                                text = { Text(m.label, color = MangaColors.OnSurface) },
                                onClick = { viewModel.setReaderMode(m); expanded = false },
                                leadingIcon = {
                                    if (m == reader.mode) Icon(Icons.Filled.Check, null, tint = MangaColors.Primary)
                                }
                            )
                        }
                    }
                }
            }
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(icon = Icons.Filled.ScreenLockPortrait,
                title = "إبقاء الشاشة مضاءة",
                subtitle = "أثناء القراءة",
                checked = reader.keepScreenOn,
                onCheckedChange = viewModel::setKeepScreen)
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(icon = Icons.Filled.AutoAwesome,
                title = "الكشف التلقائي عن Webtoon",
                subtitle = "تغيير وضع القراءة تلقائياً",
                checked = reader.autoWebtoonDetection,
                onCheckedChange = viewModel::setAutoWebtoon)
        }

        // ── Sources ───────────────────────────────────────────────────────────
        SettingsSection("المصادر") {
            MangaSource.values().forEachIndexed { i, source ->
                val enabled = app.enabledSources.contains(source.id)
                SwitchItem(
                    icon = Icons.Filled.Language,
                    title = source.displayName,
                    subtitle = source.baseUrl,
                    checked = enabled,
                    onCheckedChange = { viewModel.toggleSource(source.id, it) }
                )
                if (i < MangaSource.values().size - 1)
                    GradientDivider(Modifier.padding(horizontal = 16.dp))
            }
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SettingsItem(
                icon = Icons.Filled.Shield,
                title = "حل حماية Cloudflare",
                subtitle = "Olympus / Starz"
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = {
                        cfLauncher.launch(
                            Intent(
                                context,
                                WebViewSolverActivity::class.java
                            ).putExtra(WebViewSolverActivity.EXTRA_URL, "https://olympustaff.com")
                                .putExtra(WebViewSolverActivity.EXTRA_DOMAIN, "olympustaff.com")
                        )
                    }) { Text("Olympus") }
                    OutlinedButton(onClick = {
                        cfLauncher.launch(
                            Intent(
                                context,
                                WebViewSolverActivity::class.java
                            ).putExtra(WebViewSolverActivity.EXTRA_URL, "https://manga-starz.net")
                                .putExtra(WebViewSolverActivity.EXTRA_DOMAIN, "manga-starz.net")
                        )
                    }) { Text("Starz") }
                }
            }
        }

        // ── Downloads ─────────────────────────────────────────────────────────
        SettingsSection("التنزيلات") {
            SwitchItem(icon = Icons.Filled.Wifi,
                title = "التنزيل عبر Wi-Fi فقط",
                subtitle = "توفير البيانات الخلوية",
                checked = app.downloadOnWifiOnly,
                onCheckedChange = viewModel::setWifiOnly)
        }

        // ── Notifications ─────────────────────────────────────────────────────
        SettingsSection("الإشعارات") {
            SwitchItem(icon = Icons.Filled.Notifications,
                title = "إشعارات الفصول الجديدة",
                subtitle = "عند صدور فصل جديد من قائمة متابعتك",
                checked = app.enableNotifications,
                onCheckedChange = viewModel::setNotifications)
        }

        // ── About ─────────────────────────────────────────────────────────────
        SettingsSection("عن التطبيق") {
            SettingsItem(icon = Icons.Filled.Info, title = "الإصدار", subtitle = "1.0.0") {}
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SettingsItem(icon = Icons.Filled.Code, title = "com.exapps.mangaworld",
                subtitle = "مبني بـ Kotlin + Jetpack Compose") {}
        }

        Spacer(Modifier.height(80.dp))
    }
}

// ─── Section Container ────────────────────────────────────────────────────────

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MangaColors.Cyan,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 8.dp)
    )
    Column(
        Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp)
            .background(MangaColors.SurfaceContainer, RoundedCornerShape(16.dp))
    ) { content() }
}

// ─── Settings Row ─────────────────────────────────────────────────────────────

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    trailing: @Composable () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            Modifier.size(36.dp)
                .background(MangaColors.SurfaceHigh, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = MangaColors.PrimaryLight, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, color = MangaColors.OnSurface)
            if (subtitle != null)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MangaColors.Muted)
        }
        trailing()
    }
}

@Composable
private fun SwitchItem(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    SettingsItem(icon = icon, title = title, subtitle = subtitle) {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = MangaColors.Primary,
                uncheckedThumbColor = MangaColors.Muted,
                uncheckedTrackColor = MangaColors.SurfaceHigh,
                uncheckedBorderColor = MangaColors.OutlineVariant
            )
        )
    }
}
