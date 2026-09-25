package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.ScriptPluginLoader
import com.exapps.mangaworld.core.source.plugins.SourceEngine
import com.exapps.mangaworld.core.source.script.ScriptContract
import com.exapps.mangaworld.core.source.script.ScriptRunnerFactory
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 install gate: engine kind, byte cap, and hash pin — before any
 * compilation, let alone execution.
 */
class ScriptPluginLoaderTest {

    private val script = "function home(ctx){ return {featured: [], latest: [], trending: []}; }"

    private fun loader() = ScriptPluginLoader(
        ScriptRunnerFactory(
            ScriptTestSupport.sandbox,
            ScriptTestSupport.FakeFetcher(),
            ScriptTestSupport.logger,
            Dispatchers.Unconfined
        )
    )

    private fun manifestWithHash(bytes: ByteArray) =
        ScriptTestSupport.manifest(scriptSha256 = ScriptPluginLoader.sha256Hex(bytes))

    @Test
    fun validScriptLoads() {
        val bytes = script.toByteArray(Charsets.UTF_8)
        val result = loader().load(manifestWithHash(bytes), bytes)
        assertTrue(result.isSuccess)
        assertEquals("scriptpilot", result.getOrThrow().sourceId)
    }

    @Test
    fun hashMismatchRefused() {
        val bytes = script.toByteArray(Charsets.UTF_8)
        val result = loader().load(ScriptTestSupport.manifest(scriptSha256 = "1".repeat(64)), bytes)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("hash mismatch"))
    }

    @Test
    fun missingHashRefused() {
        val bytes = script.toByteArray(Charsets.UTF_8)
        val manifest = ScriptTestSupport.manifest().copy(scriptSha256 = null)
        assertTrue(loader().load(manifest, bytes).isFailure)
    }

    @Test
    fun oversizedScriptRefused() {
        val bytes = ByteArray(ScriptContract.SCRIPT_MAX_BYTES + 1) { 'x'.code.toByte() }
        val result = loader().load(manifestWithHash(bytes), bytes)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("exceeds"))
    }

    @Test
    fun nonScriptManifestRefused() {
        val bytes = script.toByteArray(Charsets.UTF_8)
        val manifest = ScriptTestSupport.manifest(engine = SourceEngine.MADARA, bridgeApi = null)
        assertTrue(loader().load(manifest, bytes).isFailure)
    }

    @Test
    fun tamperedBytesFailEvenWithStaleHash() {
        // The hash binds the EXACT bytes: flip one byte after hashing.
        val bytes = script.toByteArray(Charsets.UTF_8)
        val manifest = manifestWithHash(bytes)
        bytes[10] = (bytes[10].toInt() xor 0xFF).toByte()
        assertTrue(loader().load(manifest, bytes).isFailure)
    }

    @Test
    fun siblingScriptUrlDerived() {
        assertEquals(
            "https://cdn.example/plugins/star/v2/source.js",
            ScriptPluginLoader.siblingScriptUrl("https://cdn.example/plugins/star/v2/plugin.json")
        )
        assertEquals(null, ScriptPluginLoader.siblingScriptUrl("https://cdn.example"))
        assertEquals(null, ScriptPluginLoader.siblingScriptUrl("not a url"))
    }
}
