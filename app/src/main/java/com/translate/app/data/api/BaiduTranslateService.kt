package com.translate.app.data.api

import com.translate.app.data.model.BaiduTranslationResponse
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 百度翻译 API 接口
 * 文档: https://api.fanyi.baidu.com/doc/21
 */
interface BaiduTranslateService {

    @GET("api/trans/vip/translate")
    suspend fun translate(
        @Query("q") query: String,
        @Query("from") from: String = "auto",
        @Query("to") to: String = "zh",
        @Query("appid") appId: String,
        @Query("salt") salt: String,
        @Query("sign") sign: String
    ): BaiduTranslationResponse
}
