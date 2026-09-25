package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.ManifestInvalidReason
import com.exapps.mangaworld.core.source.plugins.ManifestParser
import com.exapps.mangaworld.core.source.plugins.ManifestResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 0 gate: strict schema v1 validation. Every violation class must fail closed as
 * SCHEMA_VIOLATION (never silently ignored, never promoted to a valid manifest).
 */
class DescriptorSchemaTest {

    private fun verify(mutator: (com.fasterxml.jackson.databind.node.ObjectNode) -> Unit): ManifestResult {
        val kp = PluginTestFixtures.generateKeyPair()
        val parser = ManifestParser(
            trustedKeys = mapOf("k1" to PluginTestFixtures.rawPublicKey(kp.public)),
            host = PluginTestFixtures.HOST
        )
        val node = PluginTestFixtures.manifestTree(mutator)
        return parser.parseAndVerify(PluginTestFixtures.signManifest(node, "k1", kp.private))
    }

    private fun expectViolation(mutator: (com.fasterxml.jackson.databind.node.ObjectNode) -> Unit) {
        val result = verify(mutator)
        assertTrue(result is ManifestResult.Invalid)
        assertEquals(ManifestInvalidReason.SCHEMA_VIOLATION, (result as ManifestResult.Invalid).reason)
    }

    @Test
    fun unknownEngineNameIsSchemaViolation() {
        expectViolation { it.put("engine", "wordpress") }
    }

    @Test
    fun unknownTopLevelFieldRejected() {
        expectViolation { it.put("engin", "madara") }
    }

    @Test
    fun badIdsRejected() {
        listOf("Starz", "starz!", "", "a".repeat(65), "-lead", "has space").forEach { id ->
            expectViolation { it.put("id", id) }
        }
    }

    @Test
    fun badVersionsRejected() {
        expectViolation { it.put("version", 0) }
        expectViolation { it.put("version", -3) }
        expectViolation { it.put("minAppVersion", "8.8") }
        expectViolation { it.put("minAppVersion", "eight") }
        expectViolation { it.put("issuedAt", "yesterday") }
    }

    @Test
    fun baseUrlMustBeCleanHttps() {
        expectViolation { it.put("baseUrl", "http://starzmanga.com") }
        expectViolation { it.put("baseUrl", "https://user@starzmanga.com") }
        expectViolation { it.put("baseUrl", "https://starzmanga.com/?x=1") }
        expectViolation { it.put("baseUrl", "not a url") }
        expectViolation { it.put("baseUrl", "https://*.starzmanga.com") }
    }

    @Test
    fun configKeysWhitelistedPerEngine() {
        expectViolation { it.withObject("/config").put("dropTables", "yes") }
    }

    @Test
    fun scriptOnlyFieldsRejectedOnThemeEngines() {
        expectViolation { it.put("bridgeApi", 1) }
        expectViolation { it.put("scriptSha256", "a".repeat(64)) }
        // `paths` is api-only.
        val paths = PluginTestFixtures.mapper.createObjectNode().put("list", "data")
        expectViolation { it.replace("paths", paths) }
    }

    @Test
    fun apiEngineRequiresPaths() {
        expectViolation {
            it.put("engine", "api")
            it.remove("config")
        }
    }

    @Test
    fun scriptEngineRequiresHash() {
        // bridgeApi present so the compat gate passes and the hash rule itself is exercised.
        expectViolation {
            it.put("engine", "script")
            it.put("bridgeApi", 1)
        }
    }

    @Test
    fun hostListRules() {
        expectViolation { it.putArray("allowedHosts").add("*.starzmanga.com") }
        expectViolation { it.putArray("allowedHosts").add("starzmanga.com:8443") }
        expectViolation { it.putArray("allowedHosts").add("user@starzmanga.com") }
        expectViolation { it.putArray("allowedHosts") }
        val many = PluginTestFixtures.mapper.createArrayNode()
        repeat(17) { many.add("h$it.example.com") }
        expectViolation { it.replace("allowedHosts", many) }
    }

    @Test
    fun quotaBounds() {
        expectViolation { it.put("timeoutMs", 50) }
        expectViolation { it.put("timeoutMs", 999_999) }
        expectViolation { it.put("maxResponseMb", 0) }
        expectViolation { it.put("maxResponseMb", 500) }
    }

    @Test
    fun requiresPermissionAcceptedWhenBoolean() {
        val kp = PluginTestFixtures.generateKeyPair()
        val parser = ManifestParser(
            trustedKeys = mapOf("k1" to PluginTestFixtures.rawPublicKey(kp.public)),
            host = PluginTestFixtures.HOST
        )
        val node = PluginTestFixtures.manifestTree { it.put("requiresPermission", true) }
        val result = parser.parseAndVerify(PluginTestFixtures.signManifest(node, "k1", kp.private))
        assertTrue(result is ManifestResult.Valid)
        assertEquals(true, (result as ManifestResult.Valid).manifest.requiresPermission)

        // Absent flag defaults to false.
        val node2 = PluginTestFixtures.manifestTree()
        val result2 = parser.parseAndVerify(PluginTestFixtures.signManifest(node2, "k1", kp.private))
        assertTrue(result2 is ManifestResult.Valid)
        assertEquals(false, (result2 as ManifestResult.Valid).manifest.requiresPermission)
    }

    @Test
    fun requiresPermissionNonBooleanRejected() {
        expectViolation { it.put("requiresPermission", "yes") }
    }

    @Test
    fun customEngineRejectedForRemoteManifests() {
        // CUSTOM is builtin-only: a downloaded manifest naming it fails closed.
        expectViolation { it.put("engine", "custom") }
    }

    @Test
    fun namesAndLogoRules() {
        expectViolation { it.replace("names", PluginTestFixtures.mapper.createObjectNode()) }
        expectViolation { it.withObject("/names").put("ar", "") }
        expectViolation { it.put("logo", "/abs/path.png") }
        expectViolation { it.put("logo", "http://insecure.example/x.png") }
        expectViolation { it.put("logo", "../../etc/passwd") }
    }

    @Test
    fun absoluteLogoConstrainedToAllowlist() {
        // Off-allowlist hosts are not a trusted image surface.
        expectViolation { it.put("logo", "https://evil.example/logo.png") }
        expectViolation { it.put("logo", "https://starzmanga.com.evil.example/l.png") }
    }

    @Test
    fun absoluteLogoAcceptedOnAllowlistAndPipeline() {
        // Base + declared CDN hosts pass; so does the Cloudinary pipeline.
        // (Base tree: baseUrl starzmanga.com, allowedHosts +cdn.starzmanga.com.)
        listOf(
            "https://starzmanga.com/logo.png",
            "https://cdn.starzmanga.com/l.png",
            "https://res.cloudinary.com/demo/image/upload/x.png",
            "logos/starz.png"
        ).forEach { logo ->
            val result = verify { it.put("logo", logo) }
            assertTrue(
                "logo $logo must validate",
                result is ManifestResult.Valid
            )
        }
    }
}
