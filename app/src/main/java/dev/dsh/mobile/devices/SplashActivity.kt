package dev.dsh.mobile.devices
import dev.dsh.mobile.R

import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat

/**
 * 极速启动跳转：
 * - 已保存设备：先进入 DevicesActivity 设备管理 Hub，实时显示在线状态，
 *   用户点选目标主机进入 WorkspaceActivity 工作台（不再一冷启动就开相机）。
 * - 未保存设备：同样进入 DevicesActivity，空态视图引导「扫码快速连接」或「手动输入」。
 */
class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_splash)

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, DevicesActivity::class.java))
            finish()
        }, 800)
    }
}
