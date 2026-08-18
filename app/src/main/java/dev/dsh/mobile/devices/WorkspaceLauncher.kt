package dev.dsh.mobile.devices

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dev.dsh.mobile.core.WorkspaceEngine
import dev.dsh.mobile.native.WorkspaceActivity
import dev.dsh.mobile.web.WebActivity

/**
 * 工作台启动器（Gate 3）：统一入口，按 workspace_engine 决定走 Web 工作台
 * 或原生回退。所有入口（设备中心 / 扫码 / 通知）都经由此处。
 *
 * 参数：
 * - baseUrl：主机 18640 地址（web 模式；token 由 WebActivity 从 HostStore 读取）
 * - hostBaseUrl：主机地址（native 回退模式）
 * - sessionId：可选，通知深链定位
 */
class WorkspaceLauncher : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val baseUrl = intent.getStringExtra("baseUrl")?.takeIf { it.isNotEmpty() }
        val hostBaseUrl = intent.getStringExtra("hostBaseUrl")?.takeIf { it.isNotEmpty() }
        val sessionId = intent.getStringExtra("sessionId")?.takeIf { it.isNotEmpty() }

        val engine = WorkspaceEngine.get(this)
        val target: Intent = if (engine == WorkspaceEngine.NATIVE && hostBaseUrl != null) {
            // 原生回退（仅观察期故障回退；Gate 6 归档后删除）
            Intent(this, WorkspaceActivity::class.java).apply {
                putExtra("hostBaseUrl", hostBaseUrl)
                if (sessionId != null) putExtra("sessionId", sessionId)
            }
        } else {
            val url = baseUrl ?: hostBaseUrl
            if (url == null) {
                Toast(this, "缺少主机地址")
                startActivity(Intent(this, DevicesActivity::class.java))
                finish()
                return
            }
            Intent(this, WebActivity::class.java).apply {
                putExtra("baseUrl", url)
                if (sessionId != null) putExtra("sessionId", sessionId)
            }
        }
        startActivity(target)
        finish()
    }

    private fun Toast(ctx: android.content.Context, msg: String) {
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
}
