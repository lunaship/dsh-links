package dev.deeplinks.core

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
import dev.deeplinks.R
import dev.deeplinks.native.WorkspaceActivity

/**
 * DSH 会话事件系统通知（对标 dsh-mobile 的 DshNotify 桥）：
 * - 审批请求：会话在后台时需要用户处理
 * - 任务完成 / 已停止：会话结束提示
 * 点击通知回到对应主机的工作台并直接打开该会话。
 * 仅当 App 不在前台时发（前台有审批卡/运行状态，无需打扰）。
 */
object DshNotifier {
    private const val CHANNEL_ID = "dsh_events"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(CHANNEL_ID, L.notifChannelName, NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = L.notifChannelDesc
        }
        manager.createNotificationChannel(channel)
    }

    /** 审批请求：需要审批「工具名」。 */
    fun notifyApproval(context: Context, host: Host, sessionId: String, toolName: String) {
        val notification = base(context, host, sessionId)
            .setContentTitle(L.notifNeedApproval)
            .setContentText(L.notifNeedApprovalBody.format(toolName))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        postNotification(context, notificationId(host, sessionId, 1), notification)
    }

    /** 任务完成。 */
    fun notifyTaskDone(context: Context, host: Host, sessionId: String, title: String) {
        val notification = base(context, host, sessionId)
            .setContentTitle(L.notifTaskDone)
            .setContentText(L.notifTaskDoneBody.format(title))
            .setAutoCancel(true)
            .build()
        postNotification(context, notificationId(host, sessionId, 2), notification)
    }

    /** 会话停止（非正常结束，如 interrupted/error/maxTokens）。 */
    fun notifyTaskFailed(context: Context, host: Host, sessionId: String, title: String, reason: String) {
        val notification = base(context, host, sessionId)
            .setContentTitle(L.notifTaskStopped)
            .setContentText(L.notifTaskStoppedBody.format(title, reason))
            .setAutoCancel(true)
            .build()
        postNotification(context, notificationId(host, sessionId, 3), notification)
    }

    fun cancelApproval(context: Context, host: Host, sessionId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(host, sessionId, 1))
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
            host.putInto(this)
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
        (host.slotKey.hashCode() * 31 + sessionId.hashCode() + kind * 10_007) and 0x7fffffff
}
