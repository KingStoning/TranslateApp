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
}
