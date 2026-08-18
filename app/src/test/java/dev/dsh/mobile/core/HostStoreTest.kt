package dev.dsh.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostStoreTest {

    @Test
    fun `json 序列化往返一致`() {
        val hosts = listOf(
            Host("书房", "http://10.0.0.2:18640", "tok-1"),
            Host("办公室", "https://dsh.example.com", "tok-2"),
        )
        val json = HostStore.hostsToJson(hosts)
        val parsed = HostStore.hostsFromJson(json)
        assertEquals(hosts, parsed)
    }

    @Test
    fun `非法 json 返回空列表`() {
        assertTrue(HostStore.hostsFromJson("not json").isEmpty())
        assertTrue(HostStore.hostsFromJson("").isEmpty())
        assertTrue(HostStore.hostsFromJson("[{\"name\":\"x\"}]").isEmpty()) // 缺字段
    }

    @Test
    fun `upsert 按名称去重并置顶`() {
        val existing = listOf(Host("a", "http://1", "t1"), Host("b", "http://2", "t2"))
        val result = HostStore.dedupeHosts(existing, Host("a", "http://9", "t9"))
        assertEquals(2, result.size)
        assertEquals("http://9", result[0].baseUrl)
        assertEquals(Host("b", "http://2", "t2"), result[1])
    }

    @Test
    fun `upsert 按地址去重`() {
        val existing = listOf(Host("a", "http://1", "t1"))
        val result = HostStore.dedupeHosts(existing, Host("新名字", "http://1", "t9"))
        assertEquals(1, result.size)
        assertEquals("新名字", result[0].name)
        assertEquals("t9", result[0].token)
    }

    @Test
    fun `新主机插入到列表头部`() {
        val existing = listOf(Host("a", "http://1", "t1"))
        val result = HostStore.dedupeHosts(existing, Host("c", "http://3", "t3"))
        assertEquals(listOf(Host("c", "http://3", "t3"), Host("a", "http://1", "t1")), result)
    }
}
