package dev.deeplinks.core

import java.util.LinkedHashMap

/**
 * 以「像素预算」为上限的 LRU 台账（对照 MathRenderer 的 MAX_CACHE_PIXELS 记账）。
 *
 * 为什么需要它：位图渲染缓存只按**条目数**逐出时，24 张 2048×2048 的 ARGB_8888
 * 位图就是 400MB 级常驻内存，低内存设备上会直接 OOM。条目数与总像素两个上限
 * 必须同时成立，且替换已有 key 时要把旧像素扣回去，否则账本会单调增长。
 *
 * 本类只做记账与逐出决策，不持有位图：`put` 返回被逐出的条目，由调用方决定
 * 是否需要回收（例如 recycle 位图或只依赖 GC）。
 *
 * 纯 JVM 可测（无 Android / Compose 依赖）。
 */
internal class PixelBudgetLru<K, V>(
    private val maxEntries: Int,
    private val maxPixels: Long,
    private val pixelsOf: (V) -> Long,
) {
    private val entries = LinkedHashMap<K, V>(16, 0.75f, true)

    /** 当前缓存的总像素（记账值，不含已被逐出的条目）。 */
    var pixels: Long = 0L
        private set

    val size: Int get() = entries.size

    val maxEntriesLimit: Int get() = maxEntries
    val maxPixelsLimit: Long get() = maxPixels

    @Synchronized
    fun get(key: K): V? = entries[key]

    @Synchronized
    fun containsKey(key: K): Boolean = entries.containsKey(key)

    /** 插入或替换；返回本次因条目数/像素超限而被逐出的条目。 */
    @Synchronized
    fun put(key: K, value: V): List<V> {
        entries.remove(key)?.let { pixels -= pixelsOf(it) }
        entries[key] = value
        pixels += pixelsOf(value)
        val evicted = mutableListOf<V>()
        while (entries.size > maxEntries || pixels > maxPixels) {
            val eldest = entries.entries.firstOrNull() ?: break
            entries.remove(eldest.key)
            pixels -= pixelsOf(eldest.value)
            evicted += eldest.value
        }
        return evicted
    }

    @Synchronized
    fun clear() {
        entries.clear()
        pixels = 0L
    }
}
