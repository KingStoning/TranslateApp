package com.translate.app.data.api

import com.translate.app.data.model.BaiduTranslationResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 百度翻译 API 接口
 * 文档: https://api.fanyi.baidu.com/doc/21
 */
interface BaiduTranslateService {

    /**
     * @param dict 词典资源：官方文档写的是 0 = 显示，1 = 不显示；仅开通了词典的用户需要填写。null 时不传。
     * @param tts  语音资源：同上，0 = 显示，1 = 不显示。null 时不传。
     * dict / tts 不参与签名。
     */
    @GET("api/trans/vip/translate")
    suspend fun translate(
        @Query("q") query: String,
        @Query("from") from: String = "auto",
        @Query("to") to: String = "zh",
        @Query("appid") appId: String,
        @Query("salt") salt: String,
        @Query("sign") sign: String,
        @Query("dict") dict: Int? = null,
        @Query("tts") tts: Int? = null
    ): BaiduTranslationResponse
}
