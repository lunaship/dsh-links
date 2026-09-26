package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppSettingsCacheKeyTest {

    @Test
    fun `renaming a host keeps the same cache id`() {
        val original = Host("书房", "HTTP://Host.Example:18640/", "token", "device-1", "AA:BB")
        val renamed = original.copy(name = "办公室")
        assertEquals(appSettingsHostCacheId(original), appSettingsHostCacheId(renamed))
    }

    @Test
    fun `different host identity fields do not share cache id`() {
        val base = Host("a", "https://host.example:18640", "token", "device-1", "aa")
        assertNotEquals(appSettingsHostCacheId(base), appSettingsHostCacheId(base.copy(baseUrl = "https://other.example:18640")))
        assertNotEquals(appSettingsHostCacheId(base), appSettingsHostCacheId(base.copy(certFingerprint = "bb")))
        assertNotEquals(appSettingsHostCacheId(base), appSettingsHostCacheId(base.copy(deviceId = "device-2")))
        assertNotEquals(
            appSettingsHostCacheId(base.copy(relayClient = "relay.example:8443", relayRouteId = "route-1", relayRouteSecret = "secret")),
            appSettingsHostCacheId(base.copy(relayClient = "relay.example:8443", relayRouteId = "route-2", relayRouteSecret = "secret")),
        )
    }
}
