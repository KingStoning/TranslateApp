package com.translate.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.translate.app.App
import com.translate.app.MainActivity
import com.translate.app.data.settings.BubblePrefs

/**
 * 悬浮球前台服务 —— 无障碍服务没有开启时的兜底宿主（需要悬浮窗权限，带常驻通知）。
 *
 * 无障碍服务开着时由它显示悬浮球（更稳定，见 [BubbleController]），本服务会自动退出，
 * 避免出现两个悬浮球。
 */
class FloatingBubbleService : Service() {

    private var bubble: BubbleController? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForegroundService 之后必须尽快调用 startForeground，即使马上要退出
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, createNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, createNotification())
            }
        } catch (e: Exception) {
            Log.e(TAG, "启动前台服务失败", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!BubblePrefs.isEnabled(this) || TranslateAccessibilityService.instance?.isBubbleShowing == true) {
            stopSelf()
            return START_NOT_STICKY
        }

        val controller = bubble ?: BubbleController(this, accessibilityOverlay = false).also { bubble = it }
        controller.show()
        isRunning = true
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        bubble?.hide()
        bubble = null
        isRunning = false
        super.onDestroy()
    }

    private fun createNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, App.CLIPBOARD_CHANNEL_ID)
            .setContentTitle("划词翻译")
            .setContentText("悬浮球运行中 — 开启无障碍服务后可去掉此通知")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "FloatingBubble"
        private const val NOTIFICATION_ID = 1002

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingBubbleService::class.java))
        }

        fun stop(context: Context) {
            if (!isRunning) return
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }
    }
}
