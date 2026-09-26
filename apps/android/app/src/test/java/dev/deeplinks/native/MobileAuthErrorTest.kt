package dev.deeplinks.native

import dev.deeplinks.core.DshStringsZh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class MobileAuthErrorTest {
    @Test
    fun detectsAuthException() {
        assertTrue(isMobileAuthFailure(MobileAuthException("缺少或无效的连接 token", 401)))
        assertTrue(isMobileAuthFailure(IllegalStateException(MobileAuthException("设备待主机确认", 403))))
        assertTrue(isMobileAuthFailure(IllegalStateException("设备已被吊销")))
        assertTrue(isMobileAuthFailure(java.io.IOException("REVOKED revoked")))
        assertFalse(isMobileAuthFailure(java.io.IOException("AGENT_OFFLINE agent offline")))
        assertFalse(isMobileAuthFailure(SocketTimeoutException("timeout")))
        assertFalse(isMobileAuthFailure(IllegalStateException("刷新会话失败")))
    }

    @Test
    fun userMessageForPendingVsExpired() {
        assertEquals(
            DshStringsZh.devicePendingApproval,
            mobileAuthUserMessage(MobileAuthException("设备待主机确认", 403)),
        )
        assertEquals(
            DshStringsZh.connectionAuthExpired,
            mobileAuthUserMessage(MobileAuthException("缺少或无效的连接 token", 401)),
        )
        assertEquals(
            DshStringsZh.relayRouteExpired,
            mobileAuthUserMessage(java.io.IOException("REVOKED revoked")),
        )
        assertEquals(
            DshStringsZh.certificateChanged,
            mobileAuthUserMessage(dev.deeplinks.core.PinnedSsl.CertChangedException()),
        )
        assertEquals(
            DshStringsZh.devicePendingApproval,
            mobileAuthUserMessage(MobileAuthException("waiting for approval", 403)),
        )
    }

    @Test
    fun authFailureDoesNotBlockLocalHostRemoval() {
        assertFalse(shouldBlockLocalHostRemoval(null))
        assertFalse(shouldBlockLocalHostRemoval(MobileAuthException("缺少或无效的连接 token", 401)))
        assertFalse(shouldBlockLocalHostRemoval(java.io.IOException("REVOKED revoked")))
        assertTrue(shouldBlockLocalHostRemoval(java.io.IOException("timeout")))
        assertTrue(shouldBlockLocalHostRemoval(java.io.IOException("AGENT_OFFLINE agent offline")))
    }

    @Test
    fun openAuthDropsRevokedCloudPairingButKeepsPending() {
        assertFalse(shouldDropLocalHostOnOpenAuth(java.io.IOException("REVOKED revoked")))
        assertTrue(shouldDemoteRelayOnAuth(java.io.IOException("REVOKED revoked")))
        assertTrue(shouldDropLocalHostOnOpenAuth(MobileAuthException("缺少或无效的连接 token", 401)))
        assertTrue(shouldDropLocalHostOnOpenAuth(dev.deeplinks.core.PinnedSsl.CertChangedException()))
        assertFalse(shouldDropLocalHostOnOpenAuth(MobileAuthException("设备待主机确认", 403)))
        assertFalse(shouldDropLocalHostOnOpenAuth(MobileAuthException("waiting for approval", 403)))
        assertFalse(shouldDropLocalHostOnOpenAuth(java.io.IOException("AGENT_OFFLINE agent offline")))
        assertFalse(shouldDemoteRelayOnAuth(MobileAuthException("缺少或无效的连接 token", 401)))
    }

    @Test
    fun relayConnectErrorsAreUserFacing() {
        assertEquals(
            DshStringsZh.relayRouteExpired,
            friendlyNetworkError(java.io.IOException("REVOKED revoked")),
        )
        assertEquals(
            DshStringsZh.relayAgentOffline,
            friendlyNetworkError(java.io.IOException("AGENT_OFFLINE agent offline")),
        )
        assertEquals(
            DshStringsZh.requestTooFrequent,
            friendlyNetworkError(java.io.IOException("rate limited")),
        )
    }
}
