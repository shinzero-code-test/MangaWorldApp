import com.exapps.mangaworld.R
import androidx.compose.ui.res.stringResource

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
            stringResource(R.string.more_settings), style = MaterialTheme.typography.headlineMedium,
            color = MangaColors.OnSurface, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        )

        // ── Appearance ────────────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.appearance)) {
            SettingsItem(
                icon = Icons.Filled.Palette, title = stringResource(R.string.theme),
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
                title = stringResource(R.string.settings_dynamic_colors),
                subtitle = stringResource(R.string.system_color_match),
                checked = app.useDynamicColors,
                onCheckedChange = viewModel::setDynamicColors
            )
        }

        SettingsSection(stringResource(R.string.privacy_security)) {
            SwitchItem(
                icon = Icons.Filled.Fingerprint,
                title = stringResource(R.string.app_lock_fingerprint),
                subtitle = stringResource(R.string.str_453),
                checked = app.biometricLockEnabled,
                onCheckedChange = viewModel::setBiometricLock
            )
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                icon = Icons.Filled.Security,
                title = stringResource(R.string.reader_screenshot_blocked),
                subtitle = stringResource(R.string.str_424),
                checked = app.secureReaderEnabled,
                onCheckedChange = viewModel::setSecureReader
            )
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                icon = Icons.Filled.Visibility,
                title = stringResource(R.string.settings_spoiler_default),
                subtitle = stringResource(R.string.reader_spoiler_collapse),
                checked = app.spoilerCollapseDefault,
                onCheckedChange = viewModel::setSpoilerCollapseDefault
            )
        }

        // ── Reader ────────────────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.settings_reader)) {
            SettingsItem(icon = Icons.Filled.ChromeReaderMode, title = stringResource(R.string.reading_mode),
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
                title = stringResource(R.string.keep_screen_on),
                subtitle = stringResource(R.string.while_reading),
                checked = reader.keepScreenOn,
                onCheckedChange = viewModel::setKeepScreen)
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(icon = Icons.Filled.AutoAwesome,
                title = stringResource(R.string.auto_webtoon_detection),
                subtitle = stringResource(R.string.auto_read_mode),
                checked = reader.autoWebtoonDetection,
                onCheckedChange = viewModel::setAutoWebtoon)
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                icon = Icons.Filled.VisibilityOff,
                title = stringResource(R.string.incognito_browse),
                subtitle = stringResource(R.string.str_371),
                checked = reader.incognitoMode,
                onCheckedChange = viewModel::setIncognito
            )
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                icon = Icons.Filled.FlashOn,
                title = stringResource(R.string.preload_next_chapter),
                subtitle = stringResource(R.string.str_449),
                checked = reader.smartPrefetchEnabled,
                onCheckedChange = viewModel::setSmartPrefetch
            )
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                icon = Icons.Filled.TouchApp,
                title = stringResource(R.string.haptic_feedback),
                subtitle = stringResource(R.string.str_282),
                checked = reader.hapticsEnabled,
                onCheckedChange = viewModel::setReaderHaptics
            )
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SettingsItem(icon = Icons.Filled.Tune, title = stringResource(R.string.str_342), subtitle = reader.imageFilter.label) {
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
                title = stringResource(R.string.auto_next_chapter_alt),
                subtitle = stringResource(R.string.auto_next_chapter),
                checked = reader.autoOpenNextChapter,
                onCheckedChange = viewModel::setAutoOpenNextChapter
            )
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                icon = Icons.Filled.Groups,
                title = stringResource(R.string.str_069),
                subtitle = stringResource(R.string.show_online_readers),
                checked = reader.showLiveReadersOverlay,
                onCheckedChange = viewModel::setShowLiveReadersOverlay
            )
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                icon = Icons.Filled.EmojiEmotions,
                title = stringResource(R.string.show_interaction_layer),
                subtitle = stringResource(R.string.str_064),
                checked = reader.showReactionOverlay,
                onCheckedChange = viewModel::setShowReactionOverlay
            )
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                icon = Icons.Filled.ViewCarousel,
                title = stringResource(R.string.str_445),
                subtitle = stringResource(R.string.str_313),
                checked = reader.dualPageLandscape,
                onCheckedChange = viewModel::setDualPageLandscape
            )
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                icon = Icons.Filled.VerticalAlignCenter,
                title = stringResource(R.string.str_280),
                subtitle = stringResource(R.string.str_050),
                checked = reader.webtoonAutoStitch,
                onCheckedChange = viewModel::setWebtoonAutoStitch
            )
        }

        // ── Sources ───────────────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.more_sources)) {
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
                title = stringResource(R.string.str_265),
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
        SettingsSection(stringResource(R.string.more_downloads)) {
            SwitchItem(icon = Icons.Filled.Wifi,
                title = stringResource(R.string.str_147),
                subtitle = stringResource(R.string.str_248),
                checked = app.downloadOnWifiOnly,
                onCheckedChange = viewModel::setWifiOnly)
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                icon = Icons.Filled.CloudDownload,
                title = stringResource(R.string.auto_download_new_chapters),
                subtitle = stringResource(R.string.str_245),
                checked = app.autoDownloadNewChapters,
                onCheckedChange = viewModel::setAutoDownload
            )
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SwitchItem(
                icon = Icons.Filled.DeleteSweep,
                title = stringResource(R.string.auto_delete_read_downloads),
                subtitle = stringResource(R.string.str_206),
                checked = app.autoCleanupReadDownloads,
                onCheckedChange = viewModel::setAutoCleanup
            )
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SettingsItem(
                icon = Icons.Filled.Schedule,
                title = stringResource(R.string.str_329),
                subtitle = stringResource(R.string.fmt_021, app.cleanupAfterHours)
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
                title = stringResource(R.string.str_255),
                subtitle = formatBytes(cacheSizeBytes)
            ) {
                OutlinedButton(onClick = viewModel::clearImageCache) { Text(stringResource(R.string.clear)) }
            }
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SettingsItem(
                icon = Icons.Filled.Storage,
                title = stringResource(R.string.str_256),
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

        SettingsSection(stringResource(R.string.local_backup)) {
            SettingsItem(
                icon = Icons.Filled.CloudUpload,
                title = stringResource(R.string.str_221),
                subtitle = stringResource(R.string.str_190)
            ) {
                OutlinedButton(onClick = { exportLauncher.launch("MangaWorld-backup.json") }) { Text(stringResource(R.string.export)) }
            }
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SettingsItem(
                icon = Icons.Filled.FileOpen,
                title = stringResource(R.string.import_backup),
                subtitle = stringResource(R.string.merge_local_imported)
            ) {
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) { Text(stringResource(R.string.import_label)) }
            }
        }

        SettingsSection(stringResource(R.string.settings_notifications)) {
            SettingsItem(
                icon = Icons.Filled.Notifications,
                title = stringResource(R.string.alert_mode),
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

        SettingsSection(stringResource(R.string.content_control)) {
            SettingsItem(
                icon = Icons.Filled.Block,
                title = stringResource(R.string.blocked_keywords),
                subtitle = if (app.contentBlacklist.isEmpty()) stringResource(R.string.no_blocked_keywords) else stringResource(R.string.fmt_022, app.contentBlacklist.size)
            ) {
                OutlinedButton(onClick = { blacklistText = app.contentBlacklist.joinToString("\n"); blacklistDialog = true }) {
                    Text(stringResource(R.string.manage))
                }
            }
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SettingsItem(
                icon = Icons.Filled.PersonOff,
                title = stringResource(R.string.muted_users),
                subtitle = if (app.mutedUserIds.isEmpty()) stringResource(R.string.no_muted) else stringResource(R.string.fmt_023, app.mutedUserIds.size)
            ) {
                OutlinedButton(onClick = { viewModel.setMutedUserIds(emptySet()) }, enabled = app.mutedUserIds.isNotEmpty()) {
                    Text(stringResource(R.string.clear))
                }
            }
        }

        // ── Notifications ─────────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.settings_notifications)) {
            SwitchItem(icon = Icons.Filled.Notifications,
                title = stringResource(R.string.settings_notifications_new_chapters),
                subtitle = stringResource(R.string.str_315),
                checked = app.enableNotifications,
                onCheckedChange = viewModel::setNotifications)
        }

        // ── About ─────────────────────────────────────────────────────────────
        SettingsSection(stringResource(R.string.about_app)) {
            SettingsItem(icon = Icons.Filled.Info, title = stringResource(R.string.settings_version), subtitle = com.exapps.mangaworld.BuildConfig.VERSION_NAME) {}
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SettingsItem(icon = Icons.Filled.Code, title = "com.exapps.mangaworld",
                subtitle = stringResource(R.string.str_384)) {}
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SettingsItem(icon = Icons.Filled.CloudSync, title = stringResource(R.string.str_159), subtitle = stringResource(R.string.str_151)) {
                OutlinedButton(onClick = onOpenCloudSync) { Text(stringResource(R.string.open)) }
            }
            GradientDivider(Modifier.padding(horizontal = 16.dp))
            SettingsItem(icon = Icons.Filled.BugReport, title = stringResource(R.string.diagnostics_sources_health), subtitle = stringResource(R.string.str_330)) {
                OutlinedButton(onClick = onOpenDiagnostics) { Text(stringResource(R.string.open)) }
            }
        }

        Spacer(Modifier.height(80.dp))
    }

    if (blacklistDialog) {
        AlertDialog(
            onDismissRequest = { blacklistDialog = false },
            title = { Text(stringResource(R.string.blocked_keywords)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.blacklist_hint))
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
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { blacklistDialog = false }) { Text(stringResource(R.string.cancel)) }
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
