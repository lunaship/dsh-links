package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 启动路由决策测试（方案 P0）：默认入口应该是 Workspace，而不是设备列表。
 * 纯函数，无 Android 依赖。
 */
class StartupRoutingTest {

    private val mac = Host("MacBook Pro", "https://10.0.0.2:18640", "tok-1", "dev-mac")
    private val mini = Host("Mac mini", "https://10.0.0.3:18640", "tok-2", "dev-mini")
    private val legacy = Host("旧设备", "https://10.0.0.9:18640", "tok-3")

    @Test
    fun `deviceId 优先，重命名不改变稳定身份`() {
        assertEquals("device:dev-mac", mac.stableIdentity())
        assertEquals(mac.stableIdentity(), mac.copy(name = "换了个名字").stableIdentity())
    }

    @Test
    fun `没有 deviceId 时退化到连接方式加地址，仍然重命名安全`() {
        val lan = legacy.copy(name = "A")
        val lanRenamed = legacy.copy(name = "B")
        assertEquals(lan.stableIdentity(), lanRenamed.stableIdentity())
        assertEquals("lan|https://10.0.0.9:18640", lan.stableIdentity())
    }

    @Test
    fun `同一地址的局域网与云端不会撞身份`() {
        val cloud = legacy.copy(relayClient = "r:8443", relayRouteId = "rid", relayRouteSecret = "sec")
        assertEquals(true, cloud.hasRelay)
        assertEquals(false, legacy.stableIdentity() == cloud.stableIdentity())
    }

    @Test
    fun `地址大小写差异不影响稳定身份`() {
        assertEquals(
            legacy.stableIdentity(),
            legacy.copy(baseUrl = "HTTPS://10.0.0.9:18640").stableIdentity(),
        )
    }

    @Test
    fun `没有设备时返回 null，交给配对页`() {
        assertNull(pickStartupHost(emptyList(), intentHost = null, lastIdentity = null))
        assertNull(pickStartupHost(emptyList(), intentHost = null, lastIdentity = "device:dev-mac"))
    }

    @Test
    fun `外部 Intent 指定设备时优先级最高`() {
        val picked = pickStartupHost(
            hosts = listOf(mac, mini),
            intentHost = mini,
            lastIdentity = mac.stableIdentity(),
        )
        assertEquals(mini, picked)
    }

    @Test
    fun `恢复最近使用的设备`() {
        val picked = pickStartupHost(
            hosts = listOf(mac, mini),
            intentHost = null,
            lastIdentity = mini.stableIdentity(),
        )
        assertEquals(mini, picked)
    }

    @Test
    fun `最近设备被删除时回退到列表首项`() {
        val picked = pickStartupHost(
            hosts = listOf(mac, mini),
            intentHost = null,
            lastIdentity = "device:dev-gone",
        )
        assertEquals(mac, picked)
    }

    @Test
    fun `只有一台设备时直接进入它`() {
        assertEquals(mac, pickStartupHost(listOf(mac), intentHost = null, lastIdentity = null))
    }

    @Test
    fun `最近设备被重命名后仍能恢复`() {
        val renamed = mac.copy(name = "客厅 MacBook")
        val picked = pickStartupHost(
            hosts = listOf(mini, renamed),
            intentHost = null,
            lastIdentity = mac.stableIdentity(),
        )
        assertEquals(renamed, picked)
    }

    @Test
    fun `空白 lastIdentity 不参与匹配`() {
        assertEquals(
            mac,
            pickStartupHost(listOf(mac, mini), intentHost = null, lastIdentity = "   "),
        )
    }

    @Test
    fun `有可用设备就走 Workspace`() {
        assertEquals(DshStartSurface.Workspace, resolveStartSurface(mac, undecryptable = false))
    }

    @Test
    fun `无设备或凭据不可解密都走设备页`() {
        assertEquals(DshStartSurface.Devices, resolveStartSurface(null, undecryptable = false))
        assertEquals(DshStartSurface.Devices, resolveStartSurface(mac, undecryptable = true))
        assertEquals(DshStartSurface.Devices, resolveStartSurface(null, undecryptable = true))
    }
}
