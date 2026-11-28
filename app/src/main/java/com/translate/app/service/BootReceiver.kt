package com.translate.app.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 应用更新后自动恢复：
 * 1. 有 ADB 授权时自动开启无障碍服务（无障碍服务一连接就会显示悬浮球）
 * 2. 悬浮球开关打开时，显示悬浮球
 *
 * （之前这里启动的是旧的剪贴板监听服务，并没有启动悬浮球，所以重启后悬浮球不会出现。）
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                AccessibilityEnabler.ensureEnabled(context)
                BubbleManager.ensureRunning(context)
            }
        }
    }
}
