package com.exapps.mangaworld.presentation.sources

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.exapps.mangaworld.core.integration.AppLaunchIntents
import com.exapps.mangaworld.domain.model.MangaSource
import com.exapps.mangaworld.presentation.theme.MangaColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceSettingsSheet(
    source: MangaSource,
    isEnabled: Boolean,
    isNotificationEnabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleNotification: (Boolean) -> Unit,
    onClearCookies: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var showClearConfirm by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MangaColors.Surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (source.logoDrawableRes != 0) {
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(id = source.logoDrawableRes),
                        contentDescription = source.displayName,
                        modifier = Modifier.size(36.dp).padding(end = 12.dp)
                    )
                }
                Column {
                    Text(
                        text = source.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MangaColors.OnSurface
                    )
                    Text(
                        text = source.baseUrl.removePrefix("https://"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MangaColors.Muted
                    )
                }
            }

            HorizontalDivider(color = MangaColors.Muted.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 8.dp))

            // Enable/disable source
            SourceSettingToggle(
                icon = Icons.Filled.PowerSettingsNew,
                label = "تفعيل المصدر",
                subtitle = if (isEnabled) "مفعّل" else "معطّل",
                checked = isEnabled,
                onCheckedChange = onToggleEnabled
            )

            // Enable/disable notifications
            SourceSettingToggle(
                icon = Icons.Filled.Notifications,
                label = "إشعارات المصدر",
                subtitle = if (isNotificationEnabled) "تلقائي عند فصول جديدة" else "صامت",
                checked = isNotificationEnabled,
                onCheckedChange = onToggleNotification
            )

            HorizontalDivider(color = MangaColors.Muted.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 4.dp))

            // Open in browser
            SourceSettingAction(
                icon = Icons.Filled.Language,
                label = "فتح في المتصفح",
                subtitle = source.baseUrl,
                onClick = {
                    onDismiss()
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(source.baseUrl))
                    context.startActivity(intent)
                }
            )

            // Clear cookies
            SourceSettingAction(
                icon = Icons.Filled.DeleteSweep,
                label = "مسح الكوكيز",
                subtitle = "إزالة بيانات التحقق المحفوظة",
                onClick = { showClearConfirm = true }
            )

            // Create home screen shortcut
            SourceSettingAction(
                icon = Icons.Filled.Star,
                label = "إضافة اختصار للشاشة",
                subtitle = "وصول مباشر من شاشة الموبايل",
                onClick = {
                    onDismiss()
                    createSourceShortcut(context, source)
                }
            )

            Spacer(Modifier.height(20.dp))
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            containerColor = MangaColors.Surface,
            title = { Text("مسح الكوكيز", color = MangaColors.OnSurface) },
            text = {
                Text(
                    "سيتم حذف بيانات التحقق المحفوظة لـ \"${source.displayName}\". قد تحتاج لحل تحدي Cloudflare مرة أخرى.",
                    color = MangaColors.OnSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showClearConfirm = false
                        onClearCookies()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MangaColors.Error)
                ) { Text("مسح") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("إلغاء", color = MangaColors.Muted)
                }
            }
        )
    }
}

@Composable
private fun SourceSettingToggle(
    icon: ImageVector,
    label: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MangaColors.Cyan, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = MangaColors.OnSurface, fontWeight = FontWeight.Medium)
            Text(subtitle, color = MangaColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MangaColors.Cyan,
                checkedTrackColor = MangaColors.Cyan.copy(alpha = 0.3f)
            )
        )
    }
}

@Composable
private fun SourceSettingAction(
    icon: ImageVector,
    label: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = MangaColors.OnSurfaceVariant, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = MangaColors.OnSurface, fontWeight = FontWeight.Medium)
            Text(subtitle, color = MangaColors.Muted, style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.Filled.ChevronRight, null, tint = MangaColors.Muted, modifier = Modifier.size(18.dp))
    }
}

private fun createSourceShortcut(context: Context, source: MangaSource) {
    val shortcutIntent = AppLaunchIntents.sourceBrowse(context, source.id)
    shortcutIntent.action = Intent.ACTION_VIEW

    val shortcut = android.content.pm.ShortcutInfo.Builder(context, "source_${source.id}")
        .setShortLabel(source.displayName)
        .setLongLabel("${source.displayName} — MangaWorld")
        .setIcon(android.graphics.drawable.Icon.createWithResource(context, if (source.logoDrawableRes != 0) source.logoDrawableRes else android.R.drawable.ic_menu_search))
        .setIntent(shortcutIntent)
        .build()

    val shortcutManager = context.getSystemService(android.content.pm.ShortcutManager::class.java)
    shortcutManager?.addDynamicShortcuts(mutableListOf(shortcut))
}
