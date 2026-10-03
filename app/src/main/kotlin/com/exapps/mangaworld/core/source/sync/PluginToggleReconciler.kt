package com.exapps.mangaworld.core.source.sync

import android.content.Context
import com.exapps.mangaworld.BuildConfig
import com.exapps.mangaworld.core.firebase.FirebaseRemoteConfigManager
import com.exapps.mangaworld.core.source.plugins.PluginIndexStore
import com.exapps.mangaworld.core.source.plugins.PluginStatus
import com.exapps.mangaworld.core.source.plugins.PluginTrust
import com.exapps.mangaworld.core.source.plugins.PluginTrustKeys
import com.exapps.mangaworld.domain.repository.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * D6: the single funnel for source enable/disable from every screen.
 *
 * The bug it closes: enabling a held row (index DISABLED with verified
 * bytes, plugin NOT registered) used to flip settings + clear the badge
 * while leaving nothing registered — the row then vanished entirely (no
 * badge, no approve action, no explanation), and the Settings toggle never
 * touched the index at all, so the two screens disagreed.
 *
 * Rule: an enable for a DISABLED record WITH a verifiable payload is consent
 * language — it runs the approve pipeline (re-verify current keys/policy,
 * pair the runner, register, smoke) instead of a bare settings flip. The
 * settings flip happens inside approval; on failure settings stay off and the
 * switch snaps back with the failure notice. Plain builtins (and DISABLED
 * rows without payload) keep the cheap flip plus the 9.1.1 badge-clear.
 * Disabling never touches the index (re-enable stays one tap).
 */
@Singleton
class PluginToggleReconciler @Inject constructor(
    private val engine: PluginSyncEngine,
    private val settingsRepo: SettingsRepository,
    private val trustKeys: PluginTrustKeys,
    private val remoteConfig: FirebaseRemoteConfigManager,
    private val indexStore: PluginIndexStore,
    @ApplicationContext private val appContext: Context
) {
    sealed interface ToggleOutcome {
        data object Enabled : ToggleOutcome
        data object Disabled : ToggleOutcome
        data object Approved : ToggleOutcome
        data class ApprovalFailed(val reason: String) : ToggleOutcome
    }

    suspend fun setEnabled(id: String, enabled: Boolean): ToggleOutcome {
        if (!enabled) {
            settingsRepo.toggleSource(id, false)
            return ToggleOutcome.Disabled
        }
        val rec = runCatching { indexStore.get(id) }.getOrNull()
        if (rec != null && rec.status == PluginStatus.DISABLED &&
            rec.activeVersion != null && rec.manifestJson != null
        ) {
            val outcome = runCatching {
                engine.approveHeld(
                    id = id,
                    baseDir = File(appContext.filesDir, "plugins"),
                    trustedKeys = trustKeys.current(),
                    host = PluginTrust.productionCapabilities(BuildConfig.VERSION_NAME),
                    killSwitchJson = remoteConfig.pluginKillSwitchJson(),
                    postSmoke = PostSmoke.Network()
                )
            }.getOrElse {
                PluginSyncEngine.ApproveOutcome.Failed(it.message?.take(120) ?: "error")
            }
            return when (outcome) {
                is PluginSyncEngine.ApproveOutcome.Approved -> ToggleOutcome.Approved
                is PluginSyncEngine.ApproveOutcome.Held ->
                    ToggleOutcome.ApprovalFailed(outcome.reason)
                is PluginSyncEngine.ApproveOutcome.Failed ->
                    ToggleOutcome.ApprovalFailed(outcome.reason)
                is PluginSyncEngine.ApproveOutcome.Unknown ->
                    ToggleOutcome.ApprovalFailed("unknown source")
            }
        }
        settingsRepo.toggleSource(id, true)
        // 9.1.1 badge parity (previously Sources-only): enabling a DISABLED
        // row clears the stale "needs approval" badge.
        if (rec != null && rec.status == PluginStatus.DISABLED) {
            runCatching { indexStore.put(rec.copy(status = PluginStatus.ENABLED)) }
        }
        return ToggleOutcome.Enabled
    }
}
