package com.translate.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.text.HtmlCompat
import com.translate.app.data.CredentialManager
import com.translate.app.data.api.ApiClient
import com.translate.app.data.history.HistoryRepository
import com.translate.app.data.model.TranslationResult
import com.translate.app.data.repository.BaiduApiException
import com.translate.app.data.repository.MissingCredentialsException
import com.translate.app.tts.Speaker
import com.translate.app.ui.common.TranslationResultContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.translate.app.ui.theme.TranslateAppTheme
import kotlinx.coroutines.*

/**
 * 翻译结果浮窗 Activity
 *
 * 以透明 Activity 形式呈现，Activity 本身就是浮窗，
 * 不需要 SYSTEM_ALERT_WINDOW 权限，不会崩溃。
 *
 * 工作方式：
 * 1. 接收文本，入口有三种：
 *    - ACTION_PROCESS_TEXT：选中文字 → 工具栏 / 「更多 ⋮」里的「翻译」
 *    - ACTION_SEND：选中文字 → 「分享」，或 App 的「更多 → 分享/发送」→ 选「翻译」
 *    - 悬浮球服务（同样通过 EXTRA_PROCESS_TEXT 传入）
 * 2. 在 Compose UI 中直接显示加载 → 翻译结果
 * 3. 用户点击空白处或 ✕ 按钮手动关闭（不会自动消失）
 */
class ProcessTextActivity : ComponentActivity() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /** 当前要翻译的文本；onNewIntent 时更新，Compose 会自动重新翻译 */
    private var currentText by mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 让窗口铺满屏幕但不显示状态栏/导航栏装饰
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }

        val text = extractText(intent)
        if (text.isNullOrBlank()) {
            Toast.makeText(this, R.string.no_text_selected, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        currentText = text

        setContent {
            TranslateAppTheme(dynamicColor = false) {
                TranslationPopup(
                    text = currentText,
                    onDismiss = { finish() },
                    onCopy = { copiedText ->
                        copyToClipboard(copiedText)
                    }
                )
            }
        }
    }

    /**
     * 浮窗已显示时又选中了新的文字（singleTop 复用当前实例），
     * 直接用新文本刷新，而不是什么都不做。
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val text = extractText(intent)
        if (!text.isNullOrBlank()) currentText = text
    }

    override fun onDestroy() {
        scope.cancel()
        Speaker.stop()
        super.onDestroy()
    }

    /**
     * 从各种入口的 Intent 中取出要翻译的文本。
     *
     * 不同 App / ROM 传文本的方式五花八门：
     * - PROCESS_TEXT 放在 EXTRA_PROCESS_TEXT（可能是带链接的 Spanned）
     * - SEND 一般放在 EXTRA_TEXT，但类型可能是 text/html、text/x-uri 等，
     *   有的只放在 ClipData 里，有的放在 EXTRA_HTML_TEXT
     * 按顺序逐个尝试，取到第一个非空的为止。
     */
    private fun extractText(intent: Intent?): String? {
        intent ?: return null

        val candidates = sequence {
            yield(intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString())
            yield(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())
            // ClipData：部分 App 分享时只把文本放这里，链接文本也可能被拆成多项
            intent.clipData?.let { clip ->
                val sb = StringBuilder()
                for (i in 0 until clip.itemCount) {
                    val item = clip.getItemAt(i)
                    val t = item.text ?: item.htmlText?.let { htmlToPlain(it) }
                    t?.let { sb.append(it) }
                }
                yield(sb.toString())
            }
            intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let { yield(htmlToPlain(it)) }
            yield(intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString())
        }

        return candidates
            .mapNotNull { it?.trim() }
            .firstOrNull { it.isNotEmpty() }
    }

    private fun htmlToPlain(html: String): String =
        HtmlCompat.fromHtml(html, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("translation", text))
    }
}

// ========================================================================
//  Compose 翻译浮窗 UI
// ========================================================================

/**
 * 翻译卡片：原文 → 译文，支持复制。
 * Activity 浮窗和悬浮球的系统悬浮窗共用这一个卡片。
 */
@Composable
internal fun TranslationCard(
    text: String,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit
) {
    // 状态
    var isLoading by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<TranslationResult?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val ctx = LocalContext.current

    // 执行翻译
    LaunchedEffect(text) {
        // 文本变化（onNewIntent）时重置状态
        isLoading = true
        result = null
        errorMsg = null

        // 未配置密钥时，仓库仍会先查离线词库；只有需要调用百度时才报错
        val appId = CredentialManager.getAppId(ctx)
        val secretKey = CredentialManager.getSecretKey(ctx)

        val r = ApiClient.translateRepository.translate(
            query = text,
            appId = appId,
            secretKey = secretKey
        )
        r.onSuccess { t ->
            result = t
            isLoading = false
            HistoryRepository.add(t)
            // ⚡ 不再自动关闭，用户看完后点击空白处或 ✕ 按钮手动关闭
        }.onFailure { e ->
            errorMsg = when {
                e is BaiduApiException || e is MissingCredentialsException -> e.message
                e.message?.contains("Unable to resolve host") == true -> "网络连接失败"
                e.message?.contains("timeout") == true -> "网络请求超时"
                else -> "翻译失败：${e.message ?: "未知错误"}"
            }
            isLoading = false
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // 标题栏
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Translate,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "翻译",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 内容（单词释义可能较长：限制高度，可滚动）
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
            ) {
            when {
                isLoading -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 16.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                        Text("翻译中…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                errorMsg != null -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            errorMsg!!,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                result != null -> {
                    TranslationResultContent(result = result!!, onCopy = onCopy)
                }
            }
            }
        }
    }
}

/**
 * Activity 版翻译浮窗：顶部卡片，点击卡片外区域关闭。
 */
@Composable
private fun TranslationPopup(
    text: String,
    onDismiss: () -> Unit,
    onCopy: (String) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp, start = 12.dp, end = 12.dp)
        ) {
            TranslationCard(text = text, onDismiss = onDismiss, onCopy = onCopy)
        }
    }
}
