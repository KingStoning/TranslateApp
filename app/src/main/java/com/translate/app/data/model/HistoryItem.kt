package com.translate.app.data.model

/**
 * 一条翻译历史。
 * 放在 data.model 包下，proguard 已整体 keep，Gson 序列化在 release 包中也安全。
 */
data class HistoryItem(
    val id: Long,
    val originalText: String,
    val translatedText: String,
    val fromLanguage: String,
    val toLanguage: String,
    /** 最近一次查询时间 */
    val timestamp: Long,
    /** 查询次数（同一原文重复查询会累加并移到最前） */
    val count: Int = 1,
    /** 单词模式下的词典释义，可为空 */
    val wordDetail: WordDetail? = null
)
