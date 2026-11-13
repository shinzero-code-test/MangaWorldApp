package com.exapps.mangaworld.presentation.dialog

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.exapps.mangaworld.R
import com.exapps.mangaworld.core.firebase.DEFAULT_UPDATE_URL
import com.exapps.mangaworld.core.firebase.FirebaseAnalyticsManager
import com.exapps.mangaworld.core.firebase.UpdateGateConfig
import com.exapps.mangaworld.presentation.common.UpdateGate
import com.exapps.mangaworld.presentation.theme.mangaColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-app update gate (v9.8.0).
 *
 * The app ships through **GitHub Releases**, not Google Play — there is no
 * store to update from and no store to nag in. The only way a user on a
 * broken build learns that a fixed build exists is this prompt, driven by
 * Remote Config (`min_supported_version` / `latest_version` / `update_url`).
 *
 * Two surfaces:
 *  - **Required** — the installed build is below the published minimum. The
 *    dialog is *blocking*: it cannot be dismissed, because the app cannot do
 *    its job on that build (covers and chapter images do not load). It offers
 *    the download plus a **copy link** escape hatch, because a large part of
 *    the fleet sideloads and many of those devices have no browser at all.
 *  - **Available** — the build works but is not the newest one. A dismissible
 *    notice, shown at most once per process.
 */
@Composable
fun UpdateGateHost(
    installedVersion: String,
    config: UpdateGateConfig,
    analytics: FirebaseAnalyticsManager
) {
    val context = LocalContext.current
    val colors = mangaColors()

    // Pure decision — recomputed only when the published config changes.
    val decision = remember(installedVersion, config) {
        UpdateGate.evaluate(
            installedVersion = installedVersion,
            minSupportedVersion = config.minSupportedVersion,
            latestVersion = config.latestVersion
        )
    }

    // The gate is decided ONCE per process and the decision only escalates
    // (Current → Available → Required). Remote Config lands asynchronously
    // (the persisted, locally-cached value is available immediately; the
    // network refresh lands later), so the decision is re-latched whenever the
    // published config changes — but a transient RC value (a blank fetch, a
    // partially applied config) must never let a user out of a forced-update
    // prompt.
    LaunchedEffect(decision) {
        UpdateGateSession.latch(decision)
    }
    val latched by UpdateGateSession.decision.collectAsState()

    // The soft notice is a one-shot per process: a user who dismissed it must
    // not be nagged again on the next screen or after a rotation.
    LaunchedEffect(latched) {
        when (latched) {
            UpdateGate.Decision.Available -> {
                UpdateGateSession.showNoticeOnce()
                // Once per PROCESS: a rotation must not double-count the
                // users the floor actually reached.
                if (UpdateGateSession.shouldLog(latched)) {
                    analytics.logUpdateGateNotice(config.latestVersion)
                }
            }
            // A block supersedes the notice — the two must never stack.
            UpdateGate.Decision.Required -> {
                UpdateGateSession.dismissNotice()
                if (UpdateGateSession.shouldLog(latched)) {
                    analytics.logUpdateGateBlocked(config.minSupportedVersion)
                }
            }
            UpdateGate.Decision.Current -> Unit
        }
    }
    val noticeVisible by UpdateGateSession.noticeVisible.collectAsState()

    // A Remote Config URL is remote input: only https:// is ever opened.
    val updateUrl = config.updateUrl.takeIf { UpdateGate.isUsableUpdateUrl(it) }
        ?: DEFAULT_UPDATE_URL

    if (latched == UpdateGate.Decision.Required) {
        AlertDialog(
            // Blocking: no dismissal, not even with the back gesture.
            onDismissRequest = {},
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            ),
            containerColor = colors.Surface,
            title = {
                Text(
                    text = stringResource(R.string.update_gate_required_title),
                    color = colors.OnSurface,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(
                            R.string.update_gate_required_body,
                            installedVersion,
                            config.minSupportedVersion
                        ),
                        color = colors.OnSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = updateUrl,
                        color = colors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            confirmButton = {
                Button(onClick = { openUpdateUrl(context, updateUrl) }) {
                    Text(stringResource(R.string.update_gate_download))
                }
            },
            dismissButton = {
                TextButton(onClick = { copyUpdateUrl(context, updateUrl) }) {
                    Text(
                        text = stringResource(R.string.update_gate_copy_link),
                        color = colors.Muted
                    )
                }
            }
        )
    } else if (noticeVisible) {
        AlertDialog(
            onDismissRequest = { UpdateGateSession.dismissNotice() },
            containerColor = colors.Surface,
            title = {
                Text(
                    text = stringResource(R.string.update_gate_available_title),
                    color = colors.OnSurface,
                    style = MaterialTheme.typography.titleLarge
                )
            },
            text = {
                Text(
                    text = stringResource(
                        R.string.update_gate_available_body,
                        config.latestVersion
                    ),
                    color = colors.OnSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(onClick = {
                    UpdateGateSession.dismissNotice()
                    openUpdateUrl(context, updateUrl)
                }) {
                    Text(stringResource(R.string.update_gate_download))
                }
            },
            dismissButton = {
                TextButton(onClick = { UpdateGateSession.dismissNotice() }) {
                    Text(
                        text = stringResource(R.string.update_gate_dismiss),
                        color = colors.Muted
                    )
                }
            }
        )
    }
}

/**
 * Process-scoped state of the in-app update gate.
 *
 * The gate is decided once per process and the decision only escalates:
 * a build that was found to be below the published minimum stays blocked
 * for the rest of the session even if a later Remote Config read returns a
 * blank value. The soft "a newer build exists" notice fires at most once.
 *
 * Deliberately a process-scoped object (not Compose state): the decision
 * must survive a configuration change, and the *one-shot* semantics are
 * per-process by definition.
 */
internal object UpdateGateSession {
    private val lock = Any()
    private val _decision = MutableStateFlow(UpdateGate.Decision.Current)
    private val _noticeVisible = MutableStateFlow(false)
    private var noticeClaimed = false

    /** The process-wide decision (monotonically escalating). */
    val decision: StateFlow<UpdateGate.Decision> = _decision.asStateFlow()

    /** Whether the soft "a newer build exists" notice is on screen. */
    val noticeVisible: StateFlow<Boolean> = _noticeVisible.asStateFlow()

    /** Latch [decision] and return the (possibly escalated) process decision. */
    fun latch(decision: UpdateGate.Decision): UpdateGate.Decision = synchronized(lock) {
        if (severity(decision) > severity(_decision.value)) {
            _decision.value = decision
        }
        _decision.value
    }

    /** Show the soft notice — at most once per process. */
    fun showNoticeOnce() = synchronized(lock) {
        if (noticeClaimed) {
            false
        } else {
            noticeClaimed = true
            _noticeVisible.value = true
            true
        }
    }

    /** The user dismissed the soft notice; it must not come back. */
    fun dismissNotice() {
        synchronized(lock) { noticeClaimed = true }
        _noticeVisible.value = false
    }

    private val logged = mutableSetOf<UpdateGate.Decision>()

    /**
     * Claim the one-shot right to report a decision. A rotation re-enters
     * the composable and re-runs its effect, so without this the same
     * user would be counted twice in the fleet report.
     */
    fun shouldLog(decision: UpdateGate.Decision): Boolean = synchronized(lock) {
        logged.add(decision)
    }

    private fun severity(decision: UpdateGate.Decision): Int = when (decision) {
        UpdateGate.Decision.Current -> 0
        UpdateGate.Decision.Available -> 1
        UpdateGate.Decision.Required -> 2
    }
}

/**
 * Open the release page. Devices without a browser (a large part of the
 * sideloading fleet) get the link copied to the clipboard instead of a
 * silent no-op.
 */
private fun openUpdateUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        copyUpdateUrl(context, url)
        Toast.makeText(
            context,
            R.string.update_gate_no_browser,
            Toast.LENGTH_LONG
        ).show()
    }
}

/** Copy the release link so a sideloader can hand it to a file manager. */
private fun copyUpdateUrl(context: Context, url: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.app_name), url))
    // Always confirm: on Android 13+ the system shows a brief "copied" chip,
    // but the copy-link button does NOT close the dialog (it is the blocking
    // one), so the user needs to know the tap did something.
    Toast.makeText(context, R.string.update_gate_link_copied, Toast.LENGTH_SHORT).show()
}
