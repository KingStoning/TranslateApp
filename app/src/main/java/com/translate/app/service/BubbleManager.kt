package com.translate.app.service

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.translate.app.data.settings.BubblePrefs

/**
 * 决定由谁显示悬浮球，并在各个时机把它「拉起来」：
 *
 * - 无障碍服务开着 → 由无障碍服务显示（系统保活，最稳定，无常驻通知）
 * - 否则 → 前台服务 [FloatingBubbleService] 显示（需要悬浮窗权限）
 *
 * 调用时机：打开 App、开机 / 应用更新后、无障碍服务连接或断开、设置页切换开关、快捷开关。
 */
object BubbleManager {

    private const val TAG = "BubbleManager"

    fun setEnabled(context: Context, enabled: Boolean) {
        BubblePrefs.setEnabled(context, enabled)
        ensureRunning(context)
    }

    /** 按当前设置显示 / 隐藏悬浮球（重复调用无副作用） */
    fun ensureRunning(context: Context) {
        val a11y = TranslateAccessibilityService.instance
        if (!BubblePrefs.isEnabled(context)) {
            a11y?.hideBubble()
            FloatingBubbleService.stop(context)
            return
        }
        if (a11y != null && a11y.showBubble()) {
            // 无障碍服务接管，停掉前台服务，避免出现两个悬浮球
            FloatingBubbleService.stop(context)
            return
        }
        if (canDrawOverlays(context)) {
            try {
                FloatingBubbleService.start(context)
            } catch (e: Exception) {
                // Android 12+ 后台不允许启动前台服务（如在后台被系统拉起时），下次打开 App 再启动
                Log.w(TAG, "启动悬浮球服务失败", e)
            }
        }
    }

    fun canDrawOverlays(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
}
