package dev.deeplinks.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 单图预算：超过预算的大图必须直接降级为源码块，而不是分配进缓存。 */
class MermaidRendererBudgetTest {

    @Test
    fun `预算内的常见尺寸可渲染`() {
        assertTrue(mermaidBitmapWithinBudget(1080, 1920)) // 约 2.07M 像素
        assertTrue(mermaidBitmapWithinBudget(2048, 2048)) // 4.19M 像素，仍在 16M 预算内
    }

    @Test
    fun `超过预算的尺寸被拒绝`() {
        assertFalse(mermaidBitmapWithinBudget(4096, 4096)) // 16.78M > 16M
        assertFalse(mermaidBitmapWithinBudget(6000, 3000)) // 18M
    }

    @Test
    fun `非法尺寸被拒绝`() {
        assertFalse(mermaidBitmapWithinBudget(0, 100))
        assertFalse(mermaidBitmapWithinBudget(100, 0))
        assertFalse(mermaidBitmapWithinBudget(-1, 100))
    }

    @Test
    fun `预算可注入以便设备侧下调`() {
        assertFalse(mermaidBitmapWithinBudget(1000, 1000, budget = 500_000L))
        assertTrue(mermaidBitmapWithinBudget(1000, 1000, budget = 1_000_000L))
    }

    @Test
    fun `默认预算与缓存总预算一致`() {
        assertEquals(16_000_000L, MERMAID_MAX_CACHE_PIXELS)
    }
}
