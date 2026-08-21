package dev.dsh.mobile.core

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * 配对时上报的设备显示名：优先系统「设备名称」，否则品牌/型号，
 * 末尾带 ANDROID_ID 短后缀避免多台同型号互顶（服务端按 name 唯一）。
 */
object DeviceName {
    private const val MAX_LEN = 32

    fun of(ctx: Context): String {
        val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
            ?.filter { it.isLetterOrDigit() }
            ?.takeLast(4)
            ?.lowercase()
            .orEmpty()
            .ifBlank { "0000" }
        val label = sanitize(systemLabel(ctx) ?: hardwareLabel() ?: "手机")
        val full = if (label.contains(id, ignoreCase = true)) label else "$label · $id"
        return full.take(MAX_LEN).ifBlank { "手机-$id" }
    }

    private fun systemLabel(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return null
        return runCatching {
            Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun hardwareLabel(): String? {
        val brand = Build.BRAND?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        if (model.isBlank()) return brand.ifBlank { null }
        if (brand.isBlank()) return model
        // 「Xiaomi 24129…」这类：型号已含品牌则不再重复
        if (model.contains(brand, ignoreCase = true)) return model
        return "$brand $model"
    }

    private fun sanitize(raw: String): String =
        raw.replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_LEN)
}
