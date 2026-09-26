package dev.deeplinks.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostStoreTest {

    @Test
    fun `json 序列化往返一致`() {
        val hosts = listOf(
            Host("书房", "https://10.0.0.2:18640", "tok-1", "dev-abc", "aabbcc"),
            Host("办公室", "https://dsh.example.com", "tok-2"),
        )
        val json = HostStore.hostsToJson(hosts)
        val parsed = HostStore.hostsFromJson(json)
        assertEquals(hosts, parsed)
    }

    @Test
    fun `relay 字段往返一致`() {
        val host = Host(
            name = "书房",
            baseUrl = "https://10.0.0.2:18640",
            token = "tok-1",
            deviceId = "dev-abc",
            certFingerprint = "aabbcc",
            relayClient = "relay.example:8443",
            relayRouteId = "rid",
            relayRouteSecret = "sec",
            relayTlsFingerprint = "ff",
            preferRelay = true,
        )
        assertEquals(listOf(host), HostStore.hostsFromJson(HostStore.hostsToJson(listOf(host))))
    }

    @Test
    fun `withoutRelay 清掉云端字段`() {
        val cloud = Host(
            name = "书房 · 云端",
            baseUrl = "https://10.0.0.2:18640",
            token = "tok",
            relayClient = "relay.example:8443",
            relayRouteId = "rid",
            relayRouteSecret = "sec",
            preferRelay = true,
        )
        val lan = cloud.withoutRelay()
        assertEquals(false, lan.hasRelay)
        assertEquals(false, lan.preferRelay)
        assertEquals(true, lan.needsCloudRescan)
        assertEquals("lan|书房 · 云端|https://10.0.0.2:18640", lan.slotKey)
    }

    @Test
    fun `bootstrap relay 缺键保留云端配对`() {
        val host = Host(
            name = "书房 · 云端",
            baseUrl = "https://10.0.0.2:18640",
            token = "tok",
            relayClient = "old.example:8443",
            relayRouteId = "old-rid",
            relayRouteSecret = "old-sec",
            preferRelay = true,
        )
        val unchanged = applyBootstrapRelay(host, JSONObject("""{"protocol":2}"""))
        assertEquals(host, unchanged)
    }

    @Test
    fun `bootstrap relay null 清掉云端配对`() {
        val host = Host(
            name = "书房 · 云端",
            baseUrl = "https://10.0.0.2:18640",
            token = "tok",
            relayClient = "old.example:8443",
            relayRouteId = "old-rid",
            relayRouteSecret = "old-sec",
            preferRelay = true,
        )
        val cleared = applyBootstrapRelay(host, JSONObject("""{"relay":null}"""))
        assertEquals(false, cleared.hasRelay)
        assertEquals(false, cleared.preferRelay)
        assertEquals(true, cleared.needsCloudRescan)
    }

    @Test
    fun `bootstrap relay null 对纯局域网不发明扫码标记`() {
        val lan = Host("书房", "https://10.0.0.2:18640", "tok")
        val cleared = applyBootstrapRelay(lan, JSONObject("""{"relay":null}"""))
        assertEquals(false, cleared.hasRelay)
        assertEquals(false, cleared.needsCloudRescan)
        assertEquals(lan, cleared)
    }

    @Test
    fun `bootstrap relay 对象更新路由`() {
        val host = Host(
            name = "书房 · 云端",
            baseUrl = "https://10.0.0.2:18640",
            token = "tok",
            relayClient = "old.example:8443",
            relayRouteId = "old-rid",
            relayRouteSecret = "old-sec",
            preferRelay = true,
            needsCloudRescan = true,
        )
        val root = JSONObject(
            """{"relay":{"v":2,"client":"new.example:8443","routeId":"new-rid","routeSecret":"new-sec","tlsFingerprint":"ff"}}""",
        )
        val updated = applyBootstrapRelay(host, root)
        assertEquals("new.example:8443", updated.relayClient)
        assertEquals("new-rid", updated.relayRouteId)
        assertEquals("new-sec", updated.relayRouteSecret)
        assertEquals("ff", updated.relayTlsFingerprint)
        assertEquals(true, updated.preferRelay)
        assertEquals(false, updated.needsCloudRescan)
    }

    @Test
    fun `bootstrap 残缺 relay 对象不改配对`() {
        val host = Host(
            name = "书房 · 云端",
            baseUrl = "https://10.0.0.2:18640",
            token = "tok",
            relayClient = "old.example:8443",
            relayRouteId = "old-rid",
            relayRouteSecret = "old-sec",
        )
        assertEquals(host, applyBootstrapRelay(host, JSONObject("""{"relay":{"client":"x"}}""")))
    }

    @Test
    fun `needsCloudRescan json 往返且旧记录默认为 false`() {
        val flagged = Host(
            name = "书房",
            baseUrl = "https://10.0.0.2:18640",
            token = "tok",
            needsCloudRescan = true,
        )
        assertEquals(listOf(flagged), HostStore.hostsFromJson(HostStore.hostsToJson(listOf(flagged))))
        val legacy = HostStore.hostsFromJson(
            """[{"name":"书房","baseUrl":"https://10.0.0.2:18640","token":"tok"}]""",
        )
        assertEquals(false, legacy[0].needsCloudRescan)
    }

    @Test
    fun `旧数据无 deviceId 时兼容为空串`() {
        val legacy = """[{"name":"老设备","baseUrl":"http://1:18640","token":"t"}]"""
        val parsed = HostStore.hostsFromJson(legacy)
        assertEquals("", parsed[0].deviceId)
        assertEquals("老设备", parsed[0].name)
        assertEquals("https://1:18640", parsed[0].baseUrl)
        assertEquals("", parsed[0].certFingerprint)
    }

    @Test
    fun `非法 json 返回空列表`() {
        assertTrue(HostStore.hostsFromJson("not json").isEmpty())
        assertTrue(HostStore.hostsFromJson("").isEmpty())
        assertTrue(HostStore.hostsFromJson("[{\"name\":\"x\"}]").isEmpty()) // 缺字段
    }

    @Test
    fun `按名称优先解析云端槽位`() {
        val lan = Host("书房", "https://10.0.0.2:18640", "tok-lan")
        val cloud = Host(
            name = "书房 · 云端",
            baseUrl = "https://10.0.0.2:18640",
            token = "tok-cloud",
            relayClient = "relay.example:8443",
            relayRouteId = "rid",
            relayRouteSecret = "sec",
            preferRelay = true,
        )
        val hosts = listOf(lan, cloud)
        assertEquals(cloud, hosts.resolveHost("书房 · 云端", cloud.baseUrl, true))
        assertEquals(lan, hosts.resolveHost("书房", lan.baseUrl, false))
        assertEquals(cloud, hosts.resolveHost(null, cloud.baseUrl, true))
        assertEquals(lan, hosts.resolveHost(null, lan.baseUrl, false))
    }

    @Test
    fun `旧版多台记录收敛为最近使用的那台`() {
        val mac = Host("Mac", "https://10.0.0.2:18640", "t1", deviceId = "dev-mac")
        val mini = Host("mini", "https://10.0.0.3:18640", "t2", deviceId = "dev-mini")
        assertEquals(mini, HostStore.singleHostOf(listOf(mac, mini), lastIdentity = "device:dev-mini"))
    }

    @Test
    fun `无最近记录时取列表首项`() {
        val mac = Host("Mac", "https://10.0.0.2:18640", "t1")
        val mini = Host("mini", "https://10.0.0.3:18640", "t2")
        assertEquals(mac, HostStore.singleHostOf(listOf(mac, mini), lastIdentity = null))
        assertEquals(mac, HostStore.singleHostOf(listOf(mac, mini), lastIdentity = "device:gone"))
    }

    @Test
    fun `空列表没有设备`() {
        assertEquals(null, HostStore.singleHostOf(emptyList(), lastIdentity = "device:dev-mac"))
    }
}
