package com.translate.app.service

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log

/**
 * 自动开启无障碍服务。
 *
 * 安卓不允许应用自己打开无障碍服务（防止恶意应用），唯一的例外是拥有
 * WRITE_SECURE_SETTINGS 权限。这个权限只能用电脑通过 ADB 授予一次：
 *
 *     adb shell pm grant com.translate.app android.permission.WRITE_SECURE_SETTINGS
 *
 * 授权后（重装 / 更新 App 不会丢失，卸载后需重新授权）：
 * - App 每次启动、开机、更新后，都会自动把无障碍服务写回「已开启」
 * - 系统在「强行停止 / 清理后台」时会把无障碍服务关掉，下次 App 被拉起时自动恢复
 */
object AccessibilityEnabler {

    private const val TAG = "A11yEnabler"

    fun adbCommand(context: Context) =
        "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"

    private const val PREFS = "translate_settings"
    private const val KEY_AUTO = "auto_enable_accessibility"

    /** 用户可以在设置里关掉「自动保持开启」，否则手动关闭无障碍后会被自动打开 */
    fun isAutoEnableOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_AUTO, true)

    fun setAutoEnable(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_AUTO, on).apply()
    }

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
                PackageManager.PERMISSION_GRANTED

    private fun component(context: Context) =
        ComponentName(context, TranslateAccessibilityService::class.java)

    private fun enabledList(context: Context): MutableList<String> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').filter { it.isNotBlank() }.toMutableList()

    /** 系统设置里是否已开启本应用的无障碍服务 */
    fun isEnabledInSettings(context: Context): Boolean {
        val me = component(context)
        return enabledList(context).any { ComponentName.unflattenFromString(it) == me }
    }

    /**
     * 有权限时确保无障碍服务处于开启状态。
     * @param restartIfStuck 设置里显示已开启、但服务实际没在运行（部分系统被杀后会这样）时，
     *   关掉再打开一次，促使系统重新绑定。只应在服务有足够时间启动之后调用。
     * @param force 忽略「自动保持开启」开关（用户在设置页主动点「立即开启」时）
     * @return 当前是否已开启（无权限时返回系统设置里的实际状态）
     */
    fun ensureEnabled(context: Context, restartIfStuck: Boolean = false, force: Boolean = false): Boolean {
        if (!hasPermission(context)) return isEnabledInSettings(context)
        if (!force && !isAutoEnableOn(context)) return isEnabledInSettings(context)
        return try {
            val cr = context.contentResolver
            val me = component(context)
            val list = enabledList(context)
            val present = list.any { ComponentName.unflattenFromString(it) == me }
            if (!present) {
                list += me.flattenToString()
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                Log.i(TAG, "已自动开启无障碍服务")
            } else {
                if (Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                }
                if (restartIfStuck && !TranslateAccessibilityService.isRunning) {
                    Log.i(TAG, "无障碍服务已开启但未运行，重新开启")
                    val others = list.filter { ComponentName.unflattenFromString(it) != me }
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.joinToString(":"))
                            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                        } catch (e: Exception) {
                            Log.w(TAG, "重新开启无障碍服务失败", e)
                        }
                    }, 500)
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "自动开启无障碍服务失败", e)
            isEnabledInSettings(context)
        }
    }
}
