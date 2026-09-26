package dev.deeplinks.native.util

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamBannerTest {
    @Test
    fun hidesWhileHealthyOrNoSession() {
        assertEquals(
            StreamBannerKind.Hidden,
            streamBannerKind(
                hasSession = false,
                connected = false,
                failed = false,
                connecting = true,
                retrying = false,
                everConnected = false,
                quietElapsed = true,
            ),
        )
        assertEquals(
            StreamBannerKind.Hidden,
            streamBannerKind(
                hasSession = true,
                connected = true,
                failed = false,
                connecting = false,
                retrying = false,
                everConnected = true,
                quietElapsed = true,
            ),
        )
    }

    @Test
    fun firstConnectStaysQuietUntilTimeout() {
        assertEquals(
            StreamBannerKind.Hidden,
            streamBannerKind(
                hasSession = true,
                connected = false,
                failed = false,
                connecting = true,
                retrying = false,
                everConnected = false,
                quietElapsed = false,
            ),
        )
        assertEquals(
            StreamBannerKind.Connecting,
            streamBannerKind(
                hasSession = true,
                connected = false,
                failed = false,
                connecting = true,
                retrying = false,
                everConnected = false,
                quietElapsed = true,
            ),
        )
    }

    @Test
    fun dropAfterConnectShowsImmediately() {
        assertEquals(
            StreamBannerKind.Retrying,
            streamBannerKind(
                hasSession = true,
                connected = false,
                failed = false,
                connecting = false,
                retrying = true,
                everConnected = true,
                quietElapsed = false,
            ),
        )
    }

    @Test
    fun failureShowsEvenDuringQuiet() {
        assertEquals(
            StreamBannerKind.Failed,
            streamBannerKind(
                hasSession = true,
                connected = false,
                failed = true,
                connecting = false,
                retrying = false,
                everConnected = false,
                quietElapsed = false,
            ),
        )
    }
}
