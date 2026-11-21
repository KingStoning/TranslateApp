package com.translate.app.data

import com.translate.app.data.settings.AutoTranslate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoTranslateTest {

    @Test
    fun englishSelectionsTriggerTranslation() {
        listOf(
            "apple",
            "give up",
            "The quick brown fox jumps over the lazy dog.",
            "It's 5 o'clock, isn't it?",
            "  Hello, World!  ",
            "COVID-19 vaccine",
            "mother-in-law"
        ).forEach { assertTrue(it, AutoTranslate.isEnglish(it)) }
    }

    @Test
    fun nonEnglishSelectionsAreIgnored() {
        listOf(
            "",
            "a",                       // 单个字母
            "苹果",
            "这是 English 混合",        // 含中文
            "こんにちは",
            "안녕하세요",
            "123456",
            "2026-11-21 12:00",
            "\$9.99 + 20%",
            "https://example.com/page",
            "www.example.com",
            "someone@example.com",
            "x".repeat(AutoTranslate.MAX_LENGTH + 1)
        ).forEach { assertFalse(it, AutoTranslate.isEnglish(it)) }
    }
}
