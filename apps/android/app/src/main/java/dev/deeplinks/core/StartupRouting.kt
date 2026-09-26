package dev.deeplinks.core

import android.content.Intent

/**
 * 启动目的地决策（纯函数，JVM 单测覆盖）。
 *
 * 背景：设备只是运行环境，产品价值在工作区/会话。缺省入口因此不能是设备列表：
 * 有设备的老用户直接进最近使用的 Workspace，只有「没有设备」或「凭据不可解密」
 * 才落到设备导向页面（首次配对 / 恢复）。
 */

/** 启动落点：设备导向页（首次配对 / 凭据恢复）或工作区。 */
enum class DshStartSurface { Devices, Workspace }

/**
 * 设备稳定身份。
 *
 * [Host.slotKey] 含 `name`，设备重命名后会漂移，不能当长期身份；这里优先
 * `deviceId`，旧数据缺 `deviceId` 时退化为「连接方式 + 地址」（重命名安全）。
 */
fun Host.stableIdentity(): String {
    val id = deviceId.trim()
    if (id.isNotEmpty()) return "device:$id"
    val mode = if (hasRelay) "relay" else "lan"
    return "$mode|${baseUrl.trim().lowercase()}"
}

/**
 * 解析启动时默认打开的设备。
 *
 * 顺序：外部 Intent 指定 → 最近成功使用的设备 → 唯一设备 → 列表第一台（最近配对）。
 * 任何一步拿不到可用设备就返回 null，由调用方决定去配对页还是设备页。
 */
fun pickStartupHost(
    hosts: List<Host>,
    intentHost: Host?,
    lastIdentity: String?,
): Host? {
    intentHost?.let { return it }
    if (hosts.isEmpty()) return null
    val identity = lastIdentity?.trim()?.takeIf { it.isNotEmpty() }
    if (identity != null) {
        hosts.firstOrNull { it.stableIdentity() == identity }?.let { return it }
    }
    return hosts.first()
}

/**
 * 把「有没有可用设备」翻译成启动路由。
 * `undecryptable` 表示凭据不可解密（整表不可用），必须走设备恢复而不是硬闯工作区。
 */
fun resolveStartSurface(host: Host?, undecryptable: Boolean): DshStartSurface =
    if (host != null && !undecryptable) DshStartSurface.Workspace else DshStartSurface.Devices

/** 外部 Intent 是否真的指定了设备（缺省时 [resolveFromIntent] 会误落回第一台）。 */
fun Intent.hasHostTarget(): Boolean =
    !getStringExtra(EXTRA_HOST_NAME).isNullOrBlank() || !getStringExtra(EXTRA_HOST_BASE_URL).isNullOrBlank()

/**
 * 严格版设备解析：Intent 没带设备 extra 时返回 null，不回退到列表首项。
 * 冷启动判定默认设备时必须用它，否则任何外部 Intent 都会把用户拽到第一台设备。
 */
fun List<Host>.resolveFromIntentStrict(intent: Intent): Host? {
    if (!intent.hasHostTarget()) return null
    return resolveFromIntent(intent)
}
