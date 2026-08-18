package dev.dsh.mobile

import android.Manifest
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
        if (!canNotify(context)) return
        val notification = base(context, host, sessionId)
            .setContentTitle("需要审批")
            .setContentText("「$toolName」请求执行，点击查看")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(sessionId), notification)
    }

    /** 任务完成。 */
    fun notifyTaskDone(context: Context, host: Host, sessionId: String, title: String) {
        if (!canNotify(context)) return
        val notification = base(context, host, sessionId)
            .setContentTitle("任务完成")
            .setContentText("会话「$title」已完成")
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(sessionId), notification)
    }

    /** 会话停止（非正常结束，如 interrupted/error/maxTokens）。 */
    fun notifyTaskFailed(context: Context, host: Host, sessionId: String, title: String, reason: String) {
        if (!canNotify(context)) return
        val notification = base(context, host, sessionId)
            .setContentTitle("会话已停止")
            .setContentText("会话「$title」已停止（$reason）")
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(sessionId), notification)
    }

    /** 打开会话时清掉该会话的残留通知。 */
    fun cancelForSession(context: Context, sessionId: String) {
        NotificationManagerCompat.from(context).cancel(notificationId(sessionId))
    }

    private fun base(context: Context, host: Host, sessionId: String): NotificationCompat.Builder {
        val intent = Intent(context, WorkspaceActivity::class.java).apply {
            putExtra("hostBaseUrl", host.baseUrl)
            putExtra("sessionId", sessionId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            notificationId(sessionId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dsh_mark)
            .setContentIntent(pending)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
    }

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun notificationId(sessionId: String): Int = sessionId.hashCode() and 0x7fffffff
}
