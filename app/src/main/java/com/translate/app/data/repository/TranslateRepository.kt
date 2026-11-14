package com.translate.app.data.repository

import com.translate.app.data.api.BaiduTranslateService
import com.translate.app.data.api.SignUtil
import com.translate.app.data.dict.OfflineDict
import com.translate.app.data.model.TranslationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/** 百度接口返回的业务错误 */
class BaiduApiException(val code: String, message: String) : Exception(message)

/** 没有配置百度翻译 APP ID / 密钥 */
class MissingCredentialsException :
    Exception("请先打开 App 设置页面配置百度翻译 APP ID 和密钥")

/**
 * 翻译仓库 - 封装翻译 API 调用逻辑
 */
class TranslateRepository(
    private val baiduService: BaiduTranslateService
) {
    /**
     * 翻译文本。
     *
     * 单词模式：选中的是英文单词 / 短语且内置离线词库收录了它时，直接返回词典释义
     * （音标、各词性全部释义、词形变化、考试标签、英英释义），
     * 不请求百度：秒出结果、不耗额度、没网也能查、不需要配置密钥。
     * 其余情况调用百度翻译。
     */
    suspend fun translate(
        query: String,
        appId: String,
        secretKey: String,
        from: String = "auto",
        to: String = "zh"
    ): Result<TranslationResult> = withContext(Dispatchers.IO) {
        if (WordMode.isWordQuery(query)) {
            val detail = OfflineDict.lookup(query)
            if (detail != null) {
                return@withContext Result.success(
                    TranslationResult(
                        originalText = query.trim(),
                        translatedText = detail.parts.firstOrNull()?.means?.firstOrNull() ?: "",
                        fromLanguage = "en",
                        toLanguage = "zh",
                        wordDetail = detail
                    )
                )
            }
        }

        if (appId.isBlank() || secretKey.isBlank()) {
            return@withContext Result.failure(MissingCredentialsException())
        }
        request(query, appId, secretKey, from, to)
    }

    private suspend fun request(
        query: String,
        appId: String,
        secretKey: String,
        from: String,
        to: String
    ): Result<TranslationResult> {
        return try {
            val salt = UUID.randomUUID().toString().replace("-", "").take(10)
            val sign = SignUtil.generateSign(
                appId = appId,
                query = query,
                salt = salt,
                secretKey = secretKey
            )

            val response = baiduService.translate(
                query = query,
                from = from,
                to = to,
                appId = appId,
                salt = salt,
                sign = sign
            )

            val code = response.errorCode
            if (!code.isNullOrBlank() && code != "52000") {
                return Result.failure(BaiduApiException(code, describeError(code, response.errorMsg)))
            }

            val transResult = response.transResult
            if (transResult.isNullOrEmpty()) {
                Result.failure(Exception("翻译结果为空"))
            } else {
                Result.success(
                    TranslationResult(
                        originalText = query,
                        translatedText = transResult.joinToString("\n") { it.dst },
                        fromLanguage = response.from ?: from,
                        toLanguage = response.to ?: to
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        /** 百度错误码 → 中文说明 */
        fun describeError(code: String, msg: String?): String = when (code) {
            "52001" -> "请求超时，请重试"
            "52002" -> "百度翻译系统错误，请重试"
            "52003" -> "APP ID 无效，请检查设置中的 APP ID"
            "54000" -> "请求参数缺失"
            "54001" -> "签名错误，请检查设置中的密钥"
            "54003" -> "访问太频繁，请稍后再试"
            "54004" -> "百度翻译账户余额不足"
            "54005" -> "长文本请求太频繁，请稍后再试"
            "58000" -> "客户端 IP 不在百度后台的 IP 白名单中"
            "58001" -> "不支持该翻译方向"
            "58002" -> "百度翻译服务已关闭，请到控制台开启"
            "90107" -> "百度账号认证未通过或未生效"
            else -> "百度翻译错误 $code${msg?.let { "：$it" } ?: ""}"
        }
    }
}

/** 判断选中内容是否适合「单词模式」 */
object WordMode {
    // 1~3 个英文单词（允许 ' 和 -），如 apple / give up / mother-in-law；首尾标点会被忽略
    private val ENGLISH_WORD = Regex("^[A-Za-z][A-Za-z'’\\-]*(?: [A-Za-z][A-Za-z'’\\-]*){0,2}$")

    fun isWordQuery(text: String): Boolean {
        val t = text.trim().trim('.', ',', '!', '?', ';', ':', '"', '“', '”', '(', ')')
        return t.length in 1..40 && ENGLISH_WORD.matches(t)
    }
}
