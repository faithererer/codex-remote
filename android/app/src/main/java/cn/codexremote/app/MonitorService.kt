package cn.codexremote.app

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

class MonitorService : Service() {
    private val repository get() = (application as RemoteApplication).repository
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("monitor", "后台连接", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel("tasks", "任务状态与待处理事项", NotificationManager.IMPORTANCE_DEFAULT).apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 2, Intent(this, MonitorService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val public = Notification.Builder(this, "monitor").setSmallIcon(R.drawable.ic_remote).setContentTitle("Codex 随行正在后台监控").build()
        val notification = Notification.Builder(this, "monitor").setSmallIcon(R.drawable.ic_remote).setContentTitle("多电脑监控已开启").setContentText("维持所有已关注电脑的私网连接").setContentIntent(open).setOngoing(true).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public).addAction(Notification.Action.Builder(null, "停止监控", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1, notification)
        repository.notify = { notice ->
            val intent = Intent(this, MainActivity::class.java).setData(android.net.Uri.parse("codexremote://host/${notice.hostId}/thread/${notice.threadId}")).putExtra("hostId", notice.hostId).putExtra("threadId", notice.threadId).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val key = (notice.hostId + "/" + notice.threadId).hashCode()
            val destination = PendingIntent.getActivity(this, key, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val redacted = Notification.Builder(this, "tasks").setSmallIcon(R.drawable.ic_remote).setContentTitle("Codex 随行有任务状态更新").build()
            val update = Notification.Builder(this, "tasks").setSmallIcon(R.drawable.ic_remote).setContentTitle(notice.title).setContentText(notice.text).setAutoCancel(true).setContentIntent(destination).setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(redacted).build()
            try { manager.notify(notice.hostId + "/" + notice.threadId, 2, update) } catch (_: SecurityException) { repository.message.value = "系统未授予通知权限，请在应用设置开启" }
        }
        repository.monitor(true)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { if (intent?.action == "stop") { repository.monitor(false); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }; return START_NOT_STICKY }
    override fun onDestroy() { repository.monitor(false); repository.notify = null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
