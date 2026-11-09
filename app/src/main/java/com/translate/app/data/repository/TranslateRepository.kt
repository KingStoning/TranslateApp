package com.translate.app.data.repository

import android.content.Context
import com.translate.app.App
import com.translate.app.data.api.BaiduTranslateService
import com.translate.app.data.api.SignUtil
import com.translate.app.data.dict.DictParser
import com.translate.app.data.model.TranslationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.UUID

/** 百度接口返回的业务错误 */
class BaiduApiException(val code: String, message: String) : Exception(message)

/**
 * 翻译仓库 - 封装翻译 API 调用逻辑
 */
class TranslateRepository(
    private val baiduService: BaiduTranslateService
) {
    /**
     * 使用百度翻译 API 翻译文本。
     *
     * 选中的是单词 / 短语时（单词模式）会额外请求词典资源（音标、词性释义、词形变化、英英释义），
     * 词典需要在百度翻译开放平台开通；没开通时自动退回普通翻译。
     */
    suspend fun translate(
        query: String,
        appId: String,
        secretKey: String,
        from: String = "auto",
        to: String = "zh"
    ): Result<TranslationResult> = withContext(Dispatchers.IO) {
        if (!WordMode.isWordQuery(query)) {
            return@withContext request(query, appId, secretKey, from, to, dictFlag = null)
        }
        translateWord(query, appId, secretKey, from, to)
    }

    /**
     * 单词模式。
     *
     * 官方文档写 dict=0 表示「显示词典」，但部分资料写法相反，所以第一次会依次试 0 和 1，
     * 记住能拿到词典数据的那个值；两个都拿不到（多半是没开通词典），24 小时内不再试探，
     * 避免每次查词都多消耗一次请求。
     */
    private suspend fun translateWord(
        query: String, appId: String, secretKey: String, from: String, to: String
    ): Result<TranslationResult> {
        val prefs = App.instance.getSharedPreferences(DICT_PREFS, Context.MODE_PRIVATE)
        val knownFlag = prefs.getInt(KEY_DICT_FLAG, -1)

        if (knownFlag == 0 || knownFlag == 1) {
            val r = request(query, appId, secretKey, from, to, dictFlag = knownFlag)
            // 带 dict 参数出错（比如服务被关闭）→ 退回普通翻译，并重新试探
            if (r.isFailure && r.exceptionOrNull() is BaiduApiException) {
                prefs.edit().putInt(KEY_DICT_FLAG, -1).apply()
                return request(query, appId, secretKey, from, to, dictFlag = null)
            }
            return r.map { it.copy(isWordQuery = true) }
        }

        val now = System.currentTimeMillis()
        if (now < prefs.getLong(KEY_UNAVAILABLE_UNTIL, 0L)) {
            return request(query, appId, secretKey, from, to, dictFlag = null)
                .map { it.copy(isWordQuery = true) }
        }

        var lastSuccess: Result<TranslationResult>? = null
        for ((index, flag) in listOf(0, 1).withIndex()) {
            if (index > 0) delay(1100) // 标准版 QPS=1，避免 54003 频率限制
            val r = request(query, appId, secretKey, from, to, dictFlag = flag)
            val value = r.getOrNull()
            if (value?.wordDetail != null) {
                prefs.edit().putInt(KEY_DICT_FLAG, flag).apply()
                return Result.success(value.copy(isWordQuery = true))
            }
            if (r.isFailure) {
                // 凭据错误 / 网络错误 / 频率限制：不是「没开通词典」，不要记为不可用。
                // 百度业务错误时再用不带 dict 的普通请求兜底（真正的凭据错误会在这里如实报出）
                val result = lastSuccess
                    ?: if (r.exceptionOrNull() is BaiduApiException)
                        request(query, appId, secretKey, from, to, dictFlag = null)
                    else r
                return result.map { it.copy(isWordQuery = true) }
            }
            lastSuccess = r
        }

        prefs.edit().putLong(KEY_UNAVAILABLE_UNTIL, now + 24 * 60 * 60 * 1000L).apply()
        val fallback = lastSuccess ?: request(query, appId, secretKey, from, to, dictFlag = null)
        return fallback.map { it.copy(isWordQuery = true) }
    }

    private suspend fun request(
        query: String,
        appId: String,
        secretKey: String,
        from: String,
        to: String,
        dictFlag: Int?
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
                sign = sign,
                dict = dictFlag,
                tts = dictFlag
            )

            val code = response.errorCode
            if (!code.isNullOrBlank() && code != "52000") {
                return Result.failure(BaiduApiException(code, describeError(code, response.errorMsg)))
            }

            val transResult = response.transResult
            if (transResult.isNullOrEmpty()) {
                Result.failure(Exception("翻译结果为空"))
            } else {
                val first = transResult.first()
                val detail = if (dictFlag != null) DictParser.parse(first.dict, query.trim()) else null
                Result.success(
                    TranslationResult(
                        originalText = query,
                        translatedText = transResult.joinToString("\n") { it.dst },
                        fromLanguage = response.from ?: from,
                        toLanguage = response.to ?: to,
                        wordDetail = detail,
                        srcTtsUrl = first.srcTts?.takeIf { it.isNotBlank() },
                        dstTtsUrl = first.dstTts?.takeIf { it.isNotBlank() }
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        private const val DICT_PREFS = "baidu_dict"
        private const val KEY_DICT_FLAG = "dict_flag"
        private const val KEY_UNAVAILABLE_UNTIL = "dict_unavailable_until"

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
    // 1~3 个英文单词（允许 ' 和 -），如 apple / give up / mother-in-law
    private val ENGLISH_WORD = Regex("^[A-Za-z][A-Za-z'’\\-]*(?: [A-Za-z][A-Za-z'’\\-]*){0,2}$")

    fun isWordQuery(text: String): Boolean {
        val t = text.trim()
        return t.length in 1..40 && ENGLISH_WORD.matches(t)
    }
}
