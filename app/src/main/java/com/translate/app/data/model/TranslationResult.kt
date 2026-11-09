package com.translate.app.data.model

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/**
 * 百度翻译 API 响应
 * 出错时只有 error_code / error_msg，from / to / trans_result 都为空，所以全部可空。
 */
data class BaiduTranslationResponse(
    @SerializedName("from") val from: String?,
    @SerializedName("to") val to: String?,
    @SerializedName("trans_result") val transResult: List<BaiduTransResult>?,
    @SerializedName("error_code") val errorCode: String?,
    @SerializedName("error_msg") val errorMsg: String?
)

data class BaiduTransResult(
    @SerializedName("src") val src: String,
    @SerializedName("dst") val dst: String,
    /** 词典资源（开通词典后返回）。文档中既有 JSON 字符串也有对象两种写法，用 JsonElement 兼容 */
    @SerializedName("dict") val dict: JsonElement?,
    /** 原文 / 译文语音 mp3 链接（开通 TTS 后返回） */
    @SerializedName("src_tts") val srcTts: String?,
    @SerializedName("dst_tts") val dstTts: String?
)

/**
 * 统一的翻译结果
 */
data class TranslationResult(
    val originalText: String,
    val translatedText: String,
    val fromLanguage: String = "auto",
    val toLanguage: String = "zh",
    val timestamp: Long = System.currentTimeMillis(),
    /** 单词模式下的词典释义；非单词或未开通词典时为 null */
    val wordDetail: WordDetail? = null,
    /** 是否按单词模式查询（用于在没有词典数据时给出提示） */
    val isWordQuery: Boolean = false,
    val srcTtsUrl: String? = null,
    val dstTtsUrl: String? = null
)

/**
 * 单词释义（来自百度词典资源 simple_means / edict）
 */
data class WordDetail(
    val word: String,
    /** 英式音标 */
    val phoneticUk: String? = null,
    /** 美式音标 */
    val phoneticUs: String? = null,
    /** 其他音标（如中文拼音） */
    val phoneticOther: String? = null,
    /** 词性 + 释义，如 n. → [苹果, 苹果树] */
    val parts: List<WordPart> = emptyList(),
    /** 词形变化，如 复数 → apples */
    val exchanges: List<WordExchange> = emptyList(),
    /** 考试标签，如 高考、CET4、考研 */
    val tags: List<String> = emptyList(),
    /** 英英释义 + 例句（edict） */
    val englishDefinitions: List<EnglishDefinition> = emptyList()
) {
    val hasContent: Boolean
        get() = parts.isNotEmpty() || englishDefinitions.isNotEmpty()

    /** 一行摘要，如「n. 苹果；苹果树  v. …」，用于历史记录 */
    fun summary(maxParts: Int = 3): String =
        parts.take(maxParts).joinToString("  ") { p ->
            (if (p.part.isNotBlank()) "${p.part} " else "") + p.means.take(3).joinToString("；")
        }
}

data class WordPart(val part: String, val means: List<String>)

data class WordExchange(val label: String, val forms: List<String>)

data class EnglishDefinition(
    val pos: String,
    val definitions: List<String>,
    val examples: List<String>,
    val similarWords: List<String> = emptyList()
)

/**
 * 语言代码映射
 */
enum class Language(val code: String, val displayName: String) {
    AUTO("auto", "自动检测"),
    ZH("zh", "中文"),
    EN("en", "英文"),
    JP("jp", "日语"),
    KOR("kor", "韩语"),
    FRA("fra", "法语"),
    DE("de", "德语"),
    RU("ru", "俄语"),
    SPA("spa", "西班牙语");
}
