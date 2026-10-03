package com.exapps.mangaworld.core.firebase

import com.exapps.mangaworld.core.source.SourceUiTestFixtures
import com.exapps.mangaworld.core.source.plugins.SourceDisplay
import com.exapps.mangaworld.core.source.plugins.SourcePlugin
import com.exapps.mangaworld.core.source.plugins.SourceRegistry
import com.exapps.mangaworld.core.data.remote.scraper.MangaScraper
import com.google.android.gms.tasks.Tasks
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import io.mockk.*
import javax.inject.Provider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1: Remote Config seeds `source_<id>_enabled` defaults once, at manager
 * init — dynamic remote ids arriving later inherit an accidental `false`
 * (Firebase returns false for unknown boolean keys) and are silently
 * subtracted from `enabledSources` on the next refresh.
 *
 * The fake below models real Firebase precedence (activated fetch >
 * defaults > false) so the test proves the re-seeding fix, not the mock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirebaseRemoteConfigDefaultsTest {

    /** Faithful Firebase value semantics: fetched/activated wins, then defaults, then false. */
    private class FakeRc {
        val defaults = mutableMapOf<String, Any>()
        val fetched = mutableMapOf<String, Any?>()
        val rc: FirebaseRemoteConfig = mockk<FirebaseRemoteConfig>(relaxed = true).apply {
            every { setConfigSettingsAsync(any()) } answers { Tasks.forResult(null) }
            every { setDefaultsAsync(any<Map<String, Any>>()) } answers {
                defaults.putAll(firstArg())
                Tasks.forResult(null)
            }
            every { fetchAndActivate() } answers { Tasks.forResult(true) }
            every { getBoolean(any()) } answers { keyed(firstArg()) as Boolean }
            every { getString(any()) } answers { keyed(firstArg()) as String }
            every { getLong(any()) } answers { keyed(firstArg()) as Long }
        }

        @Suppress("UNCHECKED_CAST")
        private fun keyed(key: String): Any = when (val v = fetched[key] ?: defaults[key]) {
            is Boolean -> v
            is String -> v
            is Number -> v.toLong()
            else -> if (key.endsWith("_enabled")) false
            else if (key.endsWith("_seconds") || key.endsWith("_ms")) 0L
            else ""
        }
    }

    private fun manager(
        registry: SourceRegistry,
        fake: FakeRc
    ) = FirebaseRemoteConfigManager(
        ioDispatcher = UnconfinedTestDispatcher(),
        registryProvider = Provider { registry },
        remoteConfig = fake.rc
    )

    private fun remotePlugin(id: String): SourcePlugin {
        val scraper: MangaScraper = mockk(relaxed = true)
        return object : SourcePlugin {
            override val descriptor = SourceUiTestFixtures.registry("azora")
                .descriptorFor("azora")!!.copy(id = com.exapps.mangaworld.core.source.plugins.SourceId(id))
            override val display = SourceDisplay(0, 0)
            override val scraper = scraper
        }
    }

    @Test
    fun lateRegisteredRemoteIdIsNotDisabledByRefresh() = runTest {
        val registry = SourceUiTestFixtures.registry("hijala")
        val fake = FakeRc()
        fake.fetched["source_hijala_enabled"] = true
        val manager = manager(registry, fake)
        // A remote id arrives after init (sync-installed, boot-resumed…).
        registry.registerVerified(
            remotePlugin("newbie"),
            com.exapps.mangaworld.core.source.plugins.PluginOrigin.OFFICIAL
        )
        manager.refresh()
        // No published parameter exists for it — absence must mean enabled.
        assertFalse(manager.disabledSourceIds.value.contains("newbie"))
        assertFalse(manager.disabledSourceIds.value.contains("hijala"))
    }

    @Test
    fun explicitServerFalseStillDisables() = runTest {
        // The fix must not neuter the kill-switch: an explicitly published
        // false still disables, fetched values beat re-seeded defaults.
        val registry = SourceUiTestFixtures.registry("hijala")
        val fake = FakeRc()
        fake.fetched["source_hijala_enabled"] = false
        val manager = manager(registry, fake)
        manager.refresh()
        assertTrue(manager.disabledSourceIds.value.contains("hijala"))
    }
}
