package com.translate.app.ui.main

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.translate.app.data.CredentialManager
import com.translate.app.data.api.ApiClient
import com.translate.app.data.history.HistoryRepository
import com.translate.app.data.model.TranslationResult
import com.translate.app.data.repository.BaiduApiException
import com.translate.app.data.repository.MissingCredentialsException
import kotlinx.coroutines.launch

/**
 * 主界面 ViewModel - 管理翻译状态
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ApiClient.translateRepository
    private val context = getApplication<Application>()

    // 源文本
    var sourceText by mutableStateOf("")
        private set

    // 翻译结果
    var translationResult by mutableStateOf<TranslationResult?>(null)
        private set

    // 加载状态
    var isLoading by mutableStateOf(false)
        private set

    // 错误信息
    var errorMessage by mutableStateOf<String?>(null)
        private set

    fun onSourceTextChanged(text: String) {
        sourceText = text
        // 清除之前的错误
        if (errorMessage != null) errorMessage = null
    }

    /**
     * 执行翻译
     */
    fun translate() {
        val text = sourceText.trim()
        if (text.isEmpty()) {
            errorMessage = "请输入要翻译的文本"
            return
        }

        // 未配置密钥时，仓库仍会先查离线词库；只有需要调用百度时才报错
        val appId = CredentialManager.getAppId(context)
        val secretKey = CredentialManager.getSecretKey(context)

        isLoading = true
        errorMessage = null

        viewModelScope.launch {
            val result = repository.translate(query = text, appId = appId, secretKey = secretKey)
            result.onSuccess { translation ->
                translationResult = translation
                HistoryRepository.add(translation)
            }.onFailure { error ->
                errorMessage = when {
                    error is BaiduApiException || error is MissingCredentialsException -> error.message
                    error.message?.contains("Unable to resolve host") == true ->
                        "网络连接失败，请检查网络设置"
                    error.message?.contains("timeout") == true ->
                        "网络请求超时，请稍后重试"
                    else -> "翻译失败：${error.message ?: "未知错误"}"
                }
            }
            isLoading = false
        }
    }

    /**
     * 外部传入文本进行翻译（来自 PROCESS_TEXT 或剪贴板）
     */
    fun translateExternal(text: String) {
        sourceText = text
        translate()
    }

    fun clearError() {
        errorMessage = null
    }
}
