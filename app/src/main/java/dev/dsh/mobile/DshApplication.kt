package dev.dsh.mobile

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/** 应用入口：深色模式跟随系统（HStudio 同款做法）。 */
class DshApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
    }
}
