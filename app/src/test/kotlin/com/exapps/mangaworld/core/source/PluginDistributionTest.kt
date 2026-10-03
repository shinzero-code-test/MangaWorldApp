package com.exapps.mangaworld.core.source

import com.exapps.mangaworld.core.source.plugins.PluginDistribution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * C-9: manifest-URL approval covers userinfo and explicit non-default ports,
 * not just scheme + host. A swapped URL (even to another https host) fails
 * closed — the signature authenticates content, this gate authenticates origin.
 */
class PluginDistributionTest {

    private val index = "https://mangaworld-admin.vercel.app/plugins/index.json"

    @Test
    fun sameHostManifestApproved() {
        assertNull(
            PluginDistribution.checkManifestUrl(
                "https://mangaworld-admin.vercel.app/plugins/manonga/v1/plugin.json",
                index
            )
        )
    }

    @Test
    fun crossHostManifestRejected() {
        assertEquals(
            "manifestUrl host must match index host",
            PluginDistribution.checkManifestUrl("https://evil.example/p/plugin.json", index)
        )
    }

    @Test
    fun userinfoAndPortManifestsRejected() {
        assertEquals(
            "manifestUrl must not carry userinfo",
            PluginDistribution.checkManifestUrl(
                "https://user@mangaworld-admin.vercel.app/plugins/x/plugin.json", index
            )
        )
        assertEquals(
            "manifestUrl must not carry a non-default port",
            PluginDistribution.checkManifestUrl(
                "https://mangaworld-admin.vercel.app:8443/plugins/x/plugin.json", index
            )
        )
    }

    @Test
    fun insecureManifestRejected() {
        assertEquals(
            "manifestUrl must be https",
            PluginDistribution.checkManifestUrl("http://mangaworld-admin.vercel.app/plugins/x/plugin.json", index)
        )
    }
}
