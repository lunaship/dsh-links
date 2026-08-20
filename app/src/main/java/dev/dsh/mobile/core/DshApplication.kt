package dev.dsh.mobile.core

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/** 应用入口：深色模式跟随系统；界面语言从本地缓存初始化。 */
class DshApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        LocaleManager.init(this)
    }
}
