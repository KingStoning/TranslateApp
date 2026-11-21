package com.translate.app.data.settings

/**
 * 判断一个无障碍节点是不是选中文字工具栏里的「复制」按钮（纯函数，便于单元测试）。
 */
object CopyButton {

    /** 各系统 / 应用工具栏中「复制」按钮的文字 */
    private val LABELS = setOf("复制", "拷贝", "複製", "copy")

    /** 系统浮动工具栏菜单项的资源 id，如 android:id/copy */
    private val ID_SUFFIXES = listOf(":id/copy", ":id/action_copy", ":id/menu_copy")

    fun matches(text: String?, contentDescription: String?, viewId: String?): Boolean {
        if (viewId != null && ID_SUFFIXES.any { viewId.endsWith(it, ignoreCase = true) }) return true
        return isLabel(text) || isLabel(contentDescription)
    }

    private fun isLabel(s: String?): Boolean {
        val t = s?.trim()?.lowercase() ?: return false
        return t in LABELS
    }
}
