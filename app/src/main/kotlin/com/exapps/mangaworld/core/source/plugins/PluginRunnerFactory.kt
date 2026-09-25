package com.exapps.mangaworld.core.source.plugins

import com.exapps.mangaworld.R
import com.exapps.mangaworld.core.data.remote.scraper.DescriptorScraper
import com.exapps.mangaworld.core.data.remote.scraper.MangaScraper
import com.exapps.mangaworld.core.source.script.ScriptPluginLoader
import com.exapps.mangaworld.domain.repository.SettingsRepository
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 3 runner pairing: turns a VERIFIED manifest into a serving [MangaScraper].
 *
 * - MADARA / MANGAREADER → [DescriptorScraper] (signed baseUrl + listPath +
 *   selector deviations over the shared theme engines).
 * - SCRIPT → [ScriptPluginLoader]-built sandbox runner (needs the verified
 *   `source.js` bytes; hash re-checked at load).
 * - ASTRO / API / CUSTOM → null. No generic runners exist for those engines
 *   (builtins there are bespoke Kotlin); remote payloads naming them stay
 *   retained-but-unregistered until runners land. Returning null (instead of
 *   throwing) keeps the sync outcome typed as deferral, not failure.
 *
 * Only ever called with manifests that passed signature + schema + compat.
 * This moves no trust — it builds runners.
 */
@Singleton
class PluginRunnerFactory @Inject constructor(
    private val client: OkHttpClient,
    private val settingsRepo: SettingsRepository,
    private val scriptLoader: ScriptPluginLoader
) {

    /** Descriptor runner, or null when the engine has no generic runner. */
    fun createDescriptor(manifest: PluginManifest): MangaScraper? = try {
        when (manifest.engine) {
            SourceEngine.MADARA, SourceEngine.MANGAREADER ->
                DescriptorScraper(manifest, client, settingsRepo)
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Script runner. Null = wrong engine (defer); failure = bytes rejected
     * (hash/cap/compile — the sync layer records a rejection, not a deferral).
     */
    fun createScript(manifest: PluginManifest, scriptBytes: ByteArray?): Result<MangaScraper>? {
        if (manifest.engine != SourceEngine.SCRIPT) return null
        if (scriptBytes == null) {
            return Result.failure(IllegalStateException("script payload missing"))
        }
        return scriptLoader.load(manifest, scriptBytes)
    }

    /**
     * Last-resort display for registry-served remote ids (no APK resources).
     * Names resolve from the manifest (`SourceDisplayResolver` prefers the
     * manifest map, falling back here only when it lacks the locale);
     * `logoRes = 0` renders the letter-avatar fallback.
     */
    fun remoteDisplay(): SourceDisplay = SourceDisplay(
        nameRes = R.string.source_unknown,
        logoRes = 0
    )
}
