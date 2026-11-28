package com.translate.app.data

import com.translate.app.data.settings.CopyButton
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CopyButtonTest {

    @Test
    fun matchesCopyButtons() {
        assertTrue(CopyButton.matches("复制", null, null))
        assertTrue(CopyButton.matches(" Copy ", null, null))
        assertTrue(CopyButton.matches("COPY", null, null))
        assertTrue(CopyButton.matches("拷贝", null, null))
        assertTrue(CopyButton.matches(null, "复制", null))           // 只有图标、文字在描述里
        assertTrue(CopyButton.matches(null, null, "android:id/copy"))
        assertTrue(CopyButton.matches("", null, "com.tencent.mm:id/action_copy"))
    }

    @Test
    fun ignoresOtherButtons() {
        assertFalse(CopyButton.matches("复制链接", null, null))
        assertFalse(CopyButton.matches("Copy link", null, null))
        assertFalse(CopyButton.matches("全选", null, null))
        assertFalse(CopyButton.matches("Paste", null, "android:id/paste"))
        assertFalse(CopyButton.matches(null, null, "com.example:id/copyright"))
        assertFalse(CopyButton.matches(null, null, null))
    }

    @Test
    fun recognisesSelectionMenusByTheirOtherItems() {
        // 微信聊天长按菜单 / 系统选中工具栏
        assertTrue(CopyButton.countMenuSiblings(listOf("复制", "转发", "收藏", "删除")) >= 1)
        assertTrue(CopyButton.countMenuSiblings(listOf("Copy", "Share", "Select all", null)) >= 1)
        // 网页代码块旁的 Copy 按钮：周围只有代码、没有菜单项
        assertTrue(CopyButton.countMenuSiblings(listOf("Copy", "val x = 1", "println(x)", "Kotlin")) == 0)
        // 「复制」本身不算
        assertTrue(CopyButton.countMenuSiblings(listOf("复制", "copy", "COPY")) == 0)
    }
}
