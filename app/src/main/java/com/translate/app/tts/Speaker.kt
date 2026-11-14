package com.translate.app.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import java.net.URLEncoder
import java.util.Locale

/**
 * 朗读（系统 TextToSpeech）。
 *
 * - 根据百度返回的语种代码选择发音语言，英文单词可分别读英式 / 美式
 * - 手机没有英文语音包（或没有语音引擎）时，英文改用有道词典的真人发音音频兜底（需联网；
 *   dict.youdao.com/dictvoice 是有道网页版使用的公开地址，非官方 API，失效时仅提示下载语音包）
 * - 全局单例，第一次朗读时才初始化引擎
 */
object Speaker {

    private const val TAG = "Speaker"

    enum class Accent { DEFAULT, UK, US }

    private var tts: TextToSpeech? = null
    private var ready = false
    private var initFailed = false
    private var pending: (() -> Unit)? = null
    private var player: MediaPlayer? = null
    private lateinit var appContext: Context
    private val main = Handler(Looper.getMainLooper())

    /**
     * 朗读文本。
     * @param lang 百度语种代码（en / zh / jp / kor …），未知时按文本内容猜测
     * @param fallbackUrl 系统 TTS 不可用时播放的 mp3 链接；为空时英文自动使用有道发音
     */
    fun speak(
        context: Context,
        text: String,
        lang: String?,
        accent: Accent = Accent.DEFAULT,
        fallbackUrl: String? = null
    ) {
        if (text.isBlank()) return
        appContext = context.applicationContext
        main.post { speakInternal(text.take(TextToSpeech.getMaxSpeechInputLength() - 1), lang, accent, fallbackUrl) }
    }

    fun stop() {
        main.post {
            try { tts?.stop() } catch (_: Exception) {}
            releasePlayer()
        }
    }

    private fun speakInternal(text: String, lang: String?, accent: Accent, fallbackUrlIn: String?) {
        stopPlaying()
        val fallbackUrl = fallbackUrlIn ?: youdaoVoiceUrl(text, lang, accent)
        if (initFailed) {
            if (!playUrl(fallbackUrl)) toast("没有可用的语音引擎，请在系统设置 → 文字转语音中安装")
            return
        }
        val engine = tts
        if (engine == null || !ready) {
            pending = { speakInternal(text, lang, accent, fallbackUrlIn) }
            if (engine == null) initEngine()
            return
        }

        val locale = localeFor(lang, text, accent)
        val available = try { engine.isLanguageAvailable(locale) } catch (_: Exception) { TextToSpeech.LANG_NOT_SUPPORTED }
        if (available < TextToSpeech.LANG_AVAILABLE) {
            Log.w(TAG, "语言不可用: $locale ($available)")
            if (!playUrl(fallbackUrl)) {
                toast("手机未安装「${locale.getDisplayName(Locale.CHINA)}」语音包，可在系统设置 → 文字转语音中下载")
            }
            return
        }
        engine.language = locale
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "translate-${System.nanoTime()}")
    }

    private fun initEngine() {
        tts = TextToSpeech(appContext) { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    ready = true
                } else {
                    Log.w(TAG, "TTS 初始化失败: $status")
                    initFailed = true
                    try { tts?.shutdown() } catch (_: Exception) {}
                    tts = null
                }
                val p = pending
                pending = null
                p?.invoke()
            }
        }
    }

    private fun localeFor(lang: String?, text: String, accent: Accent): Locale {
        val code = when (lang) {
            null, "", "auto" -> guessLang(text)
            else -> lang
        }
        return when (code) {
            "en" -> if (accent == Accent.UK) Locale.UK else Locale.US
            "zh", "wyw" -> Locale.SIMPLIFIED_CHINESE
            "cht" -> Locale.TRADITIONAL_CHINESE
            "yue" -> Locale("zh", "HK")
            "jp" -> Locale.JAPAN
            "kor" -> Locale.KOREA
            "fra" -> Locale.FRANCE
            "de" -> Locale.GERMANY
            "spa" -> Locale("es", "ES")
            "ru" -> Locale("ru", "RU")
            "it" -> Locale.ITALY
            "pt" -> Locale("pt", "PT")
            "th" -> Locale("th", "TH")
            "ara" -> Locale("ar")
            "vie" -> Locale("vi", "VN")
            else -> Locale.US
        }
    }

    /** 有道词典发音：type=1 英音，type=2 美音。只用于较短的英文 */
    private fun youdaoVoiceUrl(text: String, lang: String?, accent: Accent): String? {
        val code = if (lang.isNullOrEmpty() || lang == "auto") guessLang(text) else lang
        if (code != "en" || text.length > 200) return null
        val type = if (accent == Accent.UK) 1 else 2
        return "https://dict.youdao.com/dictvoice?audio=${URLEncoder.encode(text, "UTF-8")}&type=$type"
    }

    private fun guessLang(text: String): String = when {
        text.any { it in '一'..'鿿' } -> "zh"
        text.any { it in '぀'..'ヿ' } -> "jp"
        text.any { it in '가'..'힯' } -> "kor"
        else -> "en"
    }

    // ---------------------------------------------------------------- mp3 兜底

    private fun playUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            releasePlayer()
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                // 部分链接是 http，应用默认禁止明文流量，统一升级为 https
                setDataSource(url.replaceFirst("http://", "https://"))
                setOnPreparedListener { it.start() }
                setOnCompletionListener { releasePlayer() }
                setOnErrorListener { _, _, _ ->
                    releasePlayer()
                    toast("语音播放失败")
                    true
                }
                prepareAsync()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "播放 TTS 链接失败", e)
            releasePlayer()
            false
        }
    }

    private fun stopPlaying() {
        try { tts?.stop() } catch (_: Exception) {}
        releasePlayer()
    }

    private fun releasePlayer() {
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    private fun toast(msg: String) {
        try { Toast.makeText(appContext, msg, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
    }
}
