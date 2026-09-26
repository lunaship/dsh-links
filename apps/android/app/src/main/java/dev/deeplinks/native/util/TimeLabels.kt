package dev.deeplinks.native.util

import dev.deeplinks.core.L

/** 消息行的钟点时间（HH:mm），对齐 Web 助手行末尾的时间戳。 */
fun formatClockTime(timestamp: Long): String {
    if (timestamp <= 0) return ""
    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

/** 侧栏/历史相对时间（刚刚 / n分钟 / n小时 / n天）。 */
fun relativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
    if (timestamp <= 0) return ""
    val diff = now - timestamp
    return when {
        diff < 60_000 -> L.justNowShort
        diff < 3_600_000 -> L.minutesShort.format(diff / 60_000)
        diff < 86_400_000 -> L.hoursShort.format(diff / 3_600_000)
        else -> L.daysShort.format(diff / 86_400_000)
    }
}
