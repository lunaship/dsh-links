package dev.dsh.mobile.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.dsh.mobile.R
import dev.dsh.mobile.native.WorkspaceActivity

/**
 * DSH 会话事件系统通知（对标 dsh-mobile 的 DshNotify 桥）：
 * - 审批请求：会话在后台时需要用户处理
 * - 任务完成 / 已停止：会话结束提示
 * 点击通知回到对应主机的工作台并直接打开该会话。
 * 仅当 App 不在前台时发（前台有审批卡/运行状态，无需打扰）。
 */
object DshNotifier {
    private const val CHANNEL_ID = "dsh_events"
    private const val CHANNEL_NAME = "DSH 事件"
    private const val CHANNEL_DESC = "审批、任务完成等会话事件"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = CHANNEL_DESC
        }
        manager.createNotificationChannel(channel)
    }

    /** 审批请求：需要审批「工具名」。 */
    fun notifyApproval(context: Context, host: Host, sessionId: String, toolName: String) {
        val notification = base(context, host, sessionId)
            .setContentTitle("需要审批")
            .setContentText("「$toolName」请求执行，点击查看")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        postNotification(context, notificationId(host, sessionId, 1), notification)
    }

    /** 任务完成。 */
    fun notifyTaskDone(context: Context, host: Host, sessionId: String, title: String) {
        val notification = base(context, host, sessionId)
            .setContentTitle("任务完成")
            .setContentText("会话「$title」已完成")
            .setAutoCancel(true)
            .build()
        postNotification(context, notificationId(host, sessionId, 2), notification)
    }

    /** 会话停止（非正常结束，如 interrupted/error/maxTokens）。 */
    fun notifyTaskFailed(context: Context, host: Host, sessionId: String, title: String, reason: String) {
        val notification = base(context, host, sessionId)
            .setContentTitle("会话已停止")
            .setContentText("会话「$title」已停止（$reason）")
            .setAutoCancel(true)
            .build()
        postNotification(context, notificationId(host, sessionId, 3), notification)
    }

    /** 打开会话时清掉该会话的残留通知。 */
    fun cancelForSession(context: Context, host: Host, sessionId: String) {
        val nm = NotificationManagerCompat.from(context)
        for (kind in 1..3) nm.cancel(notificationId(host, sessionId, kind))
    }

    private fun postNotification(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // 用户可能在检查后立刻撤销权限；通知是可丢失的辅助能力。
        }
    }

    private fun base(context: Context, host: Host, sessionId: String): NotificationCompat.Builder {
        val intent = Intent(context, WorkspaceActivity::class.java).apply {
            putExtra("hostBaseUrl", host.baseUrl)
            putExtra("sessionId", sessionId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            notificationId(host, sessionId, 0),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_dsh)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    }

    private fun notificationId(host: Host, sessionId: String, kind: Int): Int =
        (host.baseUrl.hashCode() * 31 + sessionId.hashCode() + kind * 10_007) and 0x7fffffff
}
