package com.translate.app.data.settings

import android.content.Context

/**
 * 「选中英文自动翻译」的开关与判定规则。
 */
object AutoTranslate {

    private const val PREFS = "translate_settings"
    private const val KEY_ENABLED = "auto_translate_on_select"

    /** 默认开启（仍需用户手动开启无障碍服务才会生效） */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** 选中内容最长多少字符时自动弹出（太长多半是误选整段） */
    const val MAX_LENGTH = 2000

    /**
     * 选中的内容是否是「英文」：
     * - 不含中文、日文假名、韩文
     * - 至少 2 个英文字母，且英文字母占非空白字符的 60% 以上（排除纯数字、符号）
     * - 不是网址 / 邮箱
     */
    fun isEnglish(raw: String): Boolean {
        val text = raw.trim()
        if (text.length !in 2..MAX_LENGTH) return false
        if (text.contains("://") || text.startsWith("www.", ignoreCase = true)) return false
        if (Regex("^\\S+@\\S+\\.\\S+$").matches(text)) return false

        var letters = 0
        var nonSpace = 0
        for (c in text) {
            if (c.isWhitespace()) continue
            nonSpace++
            when {
                c in 'a'..'z' || c in 'A'..'Z' -> letters++
                c in '一'..'鿿' || c in '㐀'..'䶿' -> return false // 中文
                c in '぀'..'ヿ' -> return false                            // 日文假名
                c in '가'..'힯' -> return false                            // 韩文
            }
        }
        return letters >= 2 && letters * 10 >= nonSpace * 6
    }
}
