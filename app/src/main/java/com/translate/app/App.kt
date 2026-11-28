package com.translate.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.translate.app.data.dict.OfflineDict
import com.translate.app.data.history.HistoryRepository
import com.translate.app.service.AccessibilityEnabler

class App : Application() {

    companion object {
        const val CLIPBOARD_CHANNEL_ID = "clipboard_monitor_channel"
        lateinit var instance: App
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        HistoryRepository.init(this)
        OfflineDict.init(this)
        OfflineDict.warmUp()
        // 任何原因拉起进程时（开机、快捷开关、打开 App…），有授权就把无障碍服务开回来
        AccessibilityEnabler.ensureEnabled(this)
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val channel = NotificationChannel(
            CLIPBOARD_CHANNEL_ID,
            "翻译悬浮球",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "显示翻译悬浮球的后台服务通知"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
