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
import com.exapps.mangaworld.presentation.utils.formatDiagnosticBytes
import com.exapps.mangaworld.presentation.utils.normalizeBlacklistInput

@Composable
fun SettingsScreen(
    onOpenDiagnostics: () -> Unit,
    onOpenCloudSync: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val app by viewModel.appSettings.collectAsStateWithLifecycle()
    val reader by viewModel.readerSettings.collectAsStateWithLifecycle()
    val cacheSizeBytes by viewModel.imageCacheSizeBytes.collectAsStateWithLifecycle()
    val backupMessage by viewModel.backupMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var blacklistDialog by remember { mutableStateOf(false) }
    var blacklistText by remember(app.contentBlacklist) { mutableStateOf(app.contentBlacklist.joinToString("\n")) }
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
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::exportBackup)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importBackup)
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
                        AppTheme.entries.forEach { theme ->
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
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                icon = Icons.Filled.ColorLens,
                title = "ألوان ديناميكية (Material You)",
                subtitle = "مطابقة ألوان النظام على Android 12+",
                checked = app.useDynamicColors,
                onCheckedChange = viewModel::setDynamicColors
            )
        }

        SettingsSection("الخصوصية والحماية") {
            SwitchItem(
                icon = Icons.Filled.Fingerprint,
                title = "قفل التطبيق بالبصمة",
                subtitle = "يطلب المصادقة عند العودة للتطبيق",
                checked = app.biometricLockEnabled,
                onCheckedChange = viewModel::setBiometricLock
            )
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                icon = Icons.Filled.Security,
                title = "حماية القارئ من اللقطات",
                subtitle = "منع لقطات الشاشة وتسجيل القارئ",
                checked = app.secureReaderEnabled,
                onCheckedChange = viewModel::setSecureReader
            )
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                icon = Icons.Filled.Visibility,
                title = "إخفاء الحرق افتراضياً",
                subtitle = "طيّ تعليقات السبويْلر حتى تكشفها يدوياً",
                checked = app.spoilerCollapseDefault,
                onCheckedChange = viewModel::setSpoilerCollapseDefault
            )
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
                        ReaderMode.entries.forEach { m ->
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
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                icon = Icons.Filled.VisibilityOff,
                title = "وضع التصفح الخفي",
                subtitle = "لا يحفظ التقدم أو السجل أثناء القراءة",
                checked = reader.incognitoMode,
                onCheckedChange = viewModel::setIncognito
            )
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                icon = Icons.Filled.FlashOn,
                title = "التحميل المسبق للفصل التالي",
                subtitle = "يبدأ عند تجاوز 50% من الفصل الحالي",
                checked = reader.smartPrefetchEnabled,
                onCheckedChange = viewModel::setSmartPrefetch
            )
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                icon = Icons.Filled.TouchApp,
                title = "الاهتزازات اللمسية",
                subtitle = "ردود فعل عند الإشارات والإكمال",
                checked = reader.hapticsEnabled,
                onCheckedChange = viewModel::setReaderHaptics
            )
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SettingsItem(icon = Icons.Filled.Tune, title = "فلتر الصور", subtitle = reader.imageFilter.label) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { expanded = true }) {
                        Text(reader.imageFilter.label, color = MangaColors.Cyan)
                        Icon(Icons.Filled.ArrowDropDown, null, tint = MangaColors.Cyan)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(MangaColors.SurfaceContainer)) {
                        ReaderImageFilter.entries.forEach { filter ->
                            DropdownMenuItem(
                                text = { Text(filter.label, color = MangaColors.OnSurface) },
                                onClick = { viewModel.setImageFilter(filter); expanded = false },
                                leadingIcon = {
                                    if (filter == reader.imageFilter) Icon(Icons.Filled.Check, null, tint = MangaColors.Primary)
                                }
                            )
                        }
                    }
                }
            }
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                icon = Icons.Filled.SkipNext,
                title = "الانتقال التلقائي للفصل التالي",
                subtitle = "افتح الفصل التالي عند إنهاء الحالي",
                checked = reader.autoOpenNextChapter,
                onCheckedChange = viewModel::setAutoOpenNextChapter
            )
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                icon = Icons.Filled.Groups,
                title = "إظهار عداد القراء المباشر",
                subtitle = "إظهار عدد القراء المتواجدين حالياً",
                checked = reader.showLiveReadersOverlay,
                onCheckedChange = viewModel::setShowLiveReadersOverlay
            )
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                icon = Icons.Filled.EmojiEmotions,
                title = "إظهار طبقة التفاعلات",
                subtitle = "إظهار التفاعلات المباشرة داخل الفصل",
                checked = reader.showReactionOverlay,
                onCheckedChange = viewModel::setShowReactionOverlay
            )
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                icon = Icons.Filled.ViewCarousel,
                title = "وضع الصفحتين أفقياً",
                subtitle = "عرض صفحتين معاً على الشاشات العريضة",
                checked = reader.dualPageLandscape,
                onCheckedChange = viewModel::setDualPageLandscape
            )
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                icon = Icons.Filled.VerticalAlignCenter,
                title = "دمج صفحات الويب تون",
                subtitle = "إزالة الفراغات بين الصور في وضع الويب تون",
                checked = reader.webtoonAutoStitch,
                onCheckedChange = viewModel::setWebtoonAutoStitch
            )
        }

        // ── Sources ───────────────────────────────────────────────────────────
        SettingsSection("المصادر") {
            MangaSource.entries.forEachIndexed { i, source ->
                val enabled = app.enabledSources.contains(source.id)
                SwitchItem(
                    icon = Icons.Filled.Language,
                    title = source.displayName,
                    subtitle = source.baseUrl,
                    checked = enabled,
                    onCheckedChange = { viewModel.toggleSource(source.id, it) }
                )
                if (i < MangaSource.entries.size - 1)
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
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                icon = Icons.Filled.DeleteSweep,
                title = "حذف التنزيلات المقروءة تلقائياً",
                subtitle = "بعد فترة من إنهاء الفصل",
                checked = app.autoCleanupReadDownloads,
                onCheckedChange = viewModel::setAutoCleanup
            )
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SettingsItem(
                icon = Icons.Filled.Schedule,
                title = "فترة الحذف التلقائي",
                subtitle = "${app.cleanupAfterHours} ساعة"
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(24, 48, 72).forEach { hours ->
                        FilterChip(
                            selected = app.cleanupAfterHours == hours,
                            onClick = { viewModel.setCleanupHours(hours) },
                            label = { Text("$hours") }
                        )
                    }
                }
            }
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SettingsItem(
                icon = Icons.Filled.Cached,
                title = "حجم كاش الصور",
                subtitle = formatBytes(cacheSizeBytes)
            ) {
                OutlinedButton(onClick = viewModel::clearImageCache) { Text("مسح") }
            }
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SettingsItem(
                icon = Icons.Filled.Storage,
                title = "حد كاش الصور",
                subtitle = "${app.imageCacheLimitMb} MB"
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(128, 250, 500).forEach { size ->
                        FilterChip(
                            selected = app.imageCacheLimitMb == size,
                            onClick = { viewModel.setImageCacheLimit(size) },
                            label = { Text("$size") }
                        )
                    }
                }
            }
        }

        SettingsSection("النسخ الاحتياطي المحلي") {
            SettingsItem(
                icon = Icons.Filled.CloudUpload,
                title = "تصدير النسخة الاحتياطية",
                subtitle = "المفضلة، السجل، التقدم، الإعدادات، والملاحظات"
            ) {
                OutlinedButton(onClick = { exportLauncher.launch("MangaWorld-backup.json") }) { Text("تصدير") }
            }
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SettingsItem(
                icon = Icons.Filled.FileOpen,
                title = "استيراد نسخة احتياطية",
                subtitle = "دمج البيانات المحلية مع الملف المستورد"
            ) {
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) { Text("استيراد") }
            }
        }

        SettingsSection("الإشعارات") {
            SettingsItem(
                icon = Icons.Filled.Notifications,
                title = "وضع التنبيهات",
                subtitle = app.notificationDeliveryMode.label
            ) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { expanded = true }) {
                        Text(app.notificationDeliveryMode.label, color = MangaColors.Cyan)
                        Icon(Icons.Filled.ArrowDropDown, null, tint = MangaColors.Cyan)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.background(MangaColors.SurfaceContainer)) {
                        NotificationDeliveryMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.label, color = MangaColors.OnSurface) },
                                onClick = { viewModel.setNotificationMode(mode); expanded = false },
                                leadingIcon = {
                                    if (mode == app.notificationDeliveryMode) Icon(Icons.Filled.Check, null, tint = MangaColors.Primary)
                                }
                            )
                        }
                    }
                }
            }
        }

        SettingsSection("التحكم بالمحتوى") {
            SettingsItem(
                icon = Icons.Filled.Block,
                title = "الكلمات المحجوبة",
                subtitle = if (app.contentBlacklist.isEmpty()) "لا توجد كلمات محجوبة" else "${app.contentBlacklist.size} كلمة"
            ) {
                OutlinedButton(onClick = { blacklistText = app.contentBlacklist.joinToString("\n"); blacklistDialog = true }) {
                    Text("إدارة")
                }
            }
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SettingsItem(
                icon = Icons.Filled.PersonOff,
                title = "المستخدمون المكتومون",
                subtitle = if (app.mutedUserIds.isEmpty()) "لا يوجد كتم" else "${app.mutedUserIds.size} مستخدم"
            ) {
                OutlinedButton(onClick = { viewModel.setMutedUserIds(emptySet()) }, enabled = app.mutedUserIds.isNotEmpty()) {
                    Text("مسح")
                }
            }
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
            SettingsItem(icon = Icons.Filled.Info, title = "الإصدار", subtitle = "2.0.0") {}
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SettingsItem(icon = Icons.Filled.Code, title = "com.exapps.mangaworld",
                subtitle = "مبني بـ Kotlin + Jetpack Compose") {}
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SettingsItem(icon = Icons.Filled.CloudSync, title = "السحابة والمزامنة", subtitle = "الحساب، Google Sign-In، ونسخ المكتبة") {
                OutlinedButton(onClick = onOpenCloudSync) { Text("فتح") }
            }
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SettingsItem(icon = Icons.Filled.BugReport, title = "التشخيص وصحة المصادر", subtitle = "فحص المصادر والودجت والكاش") {
                OutlinedButton(onClick = onOpenDiagnostics) { Text("فتح") }
            }
        }

        Spacer(Modifier.height(80.dp))
    }

    if (blacklistDialog) {
        AlertDialog(
            onDismissRequest = { blacklistDialog = false },
            title = { Text("الكلمات المحجوبة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("أدخل كلمة أو عبارة في كل سطر لإخفائها من الرئيسية/التصفح/التحديثات.")
                    OutlinedTextField(
                        value = blacklistText,
                        onValueChange = { blacklistText = it },
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MangaColors.Primary,
                            focusedTextColor = MangaColors.OnSurface,
                            unfocusedTextColor = MangaColors.OnSurface
                        )
                    )
                }
            },
            confirmButton = {
                    TextButton(onClick = {
                    viewModel.setContentBlacklist(normalizeBlacklistInput(blacklistText))
                    blacklistDialog = false
                }) { Text("حفظ") }
            },
            dismissButton = {
                TextButton(onClick = { blacklistDialog = false }) { Text("إلغاء") }
            }
        )
    }

    backupMessage?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(2500)
            viewModel.clearBackupMessage()
        }
        Snackbar(
            modifier = Modifier.padding(16.dp),
            containerColor = MangaColors.SurfaceContainer,
            contentColor = MangaColors.OnSurface
        ) { Text(message) }
    }
}

private fun formatBytes(bytes: Long): String = formatDiagnosticBytes(bytes)

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
