package dev.dsh.mobile.core
import dev.dsh.mobile.core.DeviceName

import android.content.Context
import android.provider.Settings

/** 每台安装生成唯一设备名，避免多台设备配对时同名互顶。 */
object DeviceName {
    fun of(ctx: Context): String {
        val id = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "00000000"
        return "手机-" + id.takeLast(4)
    }
}
