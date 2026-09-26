package dev.deeplinks.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 纯 JVM 测试：像素预算台账的条目数/总像素双上限与 LRU 语义。 */
class PixelBudgetCacheTest {

    private data class Entry(val key: String, val w: Int, val h: Int, val fail: Boolean = false)

    private fun cache(maxEntries: Int = 4, maxPixels: Long = 100L) =
        PixelBudgetLru<String, Entry>(
            maxEntries = maxEntries,
            maxPixels = maxPixels,
            pixelsOf = { if (it.fail) 0L else it.w.toLong() * it.h },
        )

    private fun entry(key: String, w: Int, h: Int, fail: Boolean = false) = Entry(key, w, h, fail)

    @Test
    fun `总像素永远不超过预算`() {
        val lru = cache(maxEntries = 64, maxPixels = 100L)
        repeat(20) { i ->
            lru.put("k$i", entry("k$i", 8, 8))
            assertTrue("pixels=${lru.pixels}", lru.pixels <= 100L)
        }
    }

    @Test
    fun `条目数上限独立生效`() {
        val lru = cache(maxEntries = 3, maxPixels = Long.MAX_VALUE)
        repeat(10) { i -> lru.put("k$i", entry("k$i", 1, 1)) }
        assertEquals(3, lru.size)
        assertTrue(lru.containsKey("k9"))
        assertTrue(lru.containsKey("k8"))
        assertTrue(lru.containsKey("k7"))
        assertFalse(lru.containsKey("k0"))
    }

    @Test
    fun `最久未使用的先被逐出`() {
        val lru = cache(maxEntries = 2, maxPixels = Long.MAX_VALUE)
        lru.put("a", entry("a", 1, 1))
        lru.put("b", entry("b", 1, 1))
        // 访问 a 之后，a 变成最近使用；插入 c 应逐出 b
        lru.get("a")
        lru.put("c", entry("c", 1, 1))
        assertTrue(lru.containsKey("a"))
        assertTrue(lru.containsKey("c"))
        assertFalse(lru.containsKey("b"))
    }

    @Test
    fun `替换同一个 key 会扣回旧像素`() {
        val lru = cache(maxEntries = 8, maxPixels = 100L)
        lru.put("a", entry("a", 5, 5)) // 25
        assertEquals(25L, lru.pixels)
        lru.put("a", entry("a", 4, 4)) // 16，替换而非累加
        assertEquals(16L, lru.pixels)
        assertEquals(1, lru.size)
    }

    @Test
    fun `替换后仍按预算逐出`() {
        val lru = cache(maxEntries = 8, maxPixels = 50L)
        lru.put("a", entry("a", 3, 3)) // 9
        lru.put("b", entry("b", 3, 3)) // 9 => 18
        lru.put("a", entry("a", 6, 6)) // 36 => 45
        assertEquals(45L, lru.pixels)
        // 再插一张 6x6=36 会超 50，最旧的 b 先走
        lru.put("c", entry("c", 6, 6))
        assertTrue(lru.pixels <= 50L)
        assertFalse(lru.containsKey("b"))
    }

    @Test
    fun `单条自身超预算时会被自己逐出，账本归零`() {
        val lru = cache(maxEntries = 8, maxPixels = 50L)
        val evicted = lru.put("big", entry("big", 20, 20)) // 400 > 50
        assertEquals(0L, lru.pixels)
        assertEquals(0, lru.size)
        assertEquals(listOf(entry("big", 20, 20)), evicted)
    }

    @Test
    fun `失败缓存（零像素）不影响像素账本`() {
        val lru = cache(maxEntries = 8, maxPixels = 100L)
        lru.put("ok", entry("ok", 5, 5))
        val before = lru.pixels
        lru.put("fail", entry("fail", 0, 0, fail = true))
        assertEquals(before, lru.pixels)
        assertEquals(2, lru.size)
    }

    @Test
    fun `put 返回被逐出的条目，便于调用方回收资源`() {
        val lru = cache(maxEntries = 1, maxPixels = Long.MAX_VALUE)
        lru.put("a", entry("a", 1, 1))
        val evicted = lru.put("b", entry("b", 1, 1))
        assertEquals(listOf(entry("a", 1, 1)), evicted)
    }

    @Test
    fun `clear 清空账本`() {
        val lru = cache()
        lru.put("a", entry("a", 5, 5))
        lru.clear()
        assertEquals(0L, lru.pixels)
        assertEquals(0, lru.size)
        assertNull(lru.get("a"))
    }
}
