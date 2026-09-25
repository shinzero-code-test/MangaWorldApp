package com.exapps.mangaworld.core.source.plugins

import com.exapps.mangaworld.core.firebase.FirebaseRemoteConfigManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Current verification keys: APK-pinned set merged with cross-signed Remote
 * Config additions ([PluginTrust.resolveTrustedKeys]). Bundled pilots verify
 * against [PluginTrust.pinnedKeys] directly (APK signature is their anchor);
 * everything downloaded — sync candidates and boot-resumed actives — goes
 * through here so rotation takes effect without an app release.
 */
@Singleton
class PluginTrustKeys @Inject constructor(
    private val remoteConfigManager: FirebaseRemoteConfigManager
) {
    fun current(): Map<String, ByteArray> = remoteConfigManager.pluginTrustedKeys()
}
