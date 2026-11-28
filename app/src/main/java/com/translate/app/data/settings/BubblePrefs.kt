package com.translate.app.data.settings

import android.content.Context

/** 悬浮球开关与位置 */
object BubblePrefs {
    private const val PREFS = "translate_settings"
    private const val KEY_ENABLED = "bubble_enabled"
    private const val KEY_X = "bubble_x"
    private const val KEY_Y = "bubble_y"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(c: Context): Boolean = prefs(c).getBoolean(KEY_ENABLED, false)

    fun setEnabled(c: Context, enabled: Boolean) {
        prefs(c).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun position(c: Context, defaultX: Int, defaultY: Int): Pair<Int, Int> =
        prefs(c).getInt(KEY_X, defaultX) to prefs(c).getInt(KEY_Y, defaultY)

    fun savePosition(c: Context, x: Int, y: Int) {
        prefs(c).edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }
}
