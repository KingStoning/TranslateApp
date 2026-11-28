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

    /** 选中菜单里常和「复制」一起出现的其他按钮 */
    private val MENU_SIBLINGS = setOf(
        "全选", "剪切", "粘贴", "分享", "搜索", "翻译", "查找", "转发", "收藏", "删除",
        "多选", "引用", "提醒", "搜一搜", "网页搜索", "朗读", "更多",
        "select all", "cut", "paste", "share", "search", "web search", "translate",
        "look up", "more", "forward", "delete", "reply", "quote"
    )

    /** 统计一组按钮文字里有几个是「其他菜单项」（不含复制本身） */
    fun countMenuSiblings(labels: List<String?>): Int =
        labels.mapNotNull { it?.trim()?.lowercase() }
            .filter { it in MENU_SIBLINGS }
            .distinct()
            .size

    private fun isLabel(s: String?): Boolean {
        val t = s?.trim()?.lowercase() ?: return false
        return t in LABELS
    }
}
