package com.translate.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.translate.app.App
import com.translate.app.MainActivity
import com.translate.app.ProcessTextActivity
import com.translate.app.R
import com.translate.app.ui.overlay.OverlayTranslationWindow
import kotlinx.coroutines.*

/**
 * 悬浮球服务
 *
 * 在屏幕上显示一个可拖动的蓝色圆形悬浮球。
 * 点击后按优先级获取要翻译的文本：
 *   1. 无障碍服务捕获的选中文本（无需复制）
 *   2. 剪贴板内容（兜底方案）
 * 获取文本后由服务直接显示翻译悬浮窗（不启动 Activity，后台也能弹出）。
 */
class FloatingBubbleService : Service() {

    private val exceptionHandler = CoroutineExceptionHandler { _, _ ->
        isTranslating = false
    }

    private val serviceScope = CoroutineScope(
        Dispatchers.Main + SupervisorJob() + exceptionHandler
    )
    private lateinit var windowManager: WindowManager
    private var bubbleView: ImageView? = null
    private var overlayWindow: OverlayTranslationWindow? = null
    private var clipboardProbe: View? = null

    @Volatile
    private var isTranslating = false

    // 拖动状态
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialX = 0f
    private var initialY = 0f
    private var isDragging = false

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            startForeground(NOTIFICATION_ID, createNotification())
            showBubble()
            START_STICKY
        } catch (_: Exception) {
            // startForeground 可能因无通知权限失败，不崩溃
            android.util.Log.e("FloatingBubble", "启动前台服务失败")
            START_NOT_STICKY
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        removeClipboardProbe()
        overlayWindow?.dismiss()
        overlayWindow = null
        removeBubble()
        super.onDestroy()
    }

    // ========================================================================
    //  悬浮球 UI
    // ========================================================================

    private fun showBubble() {
        if (bubbleView != null) return

        val sizePx = dpToPx(40f)  // 缩小到 40dp
        val paddingPx = dpToPx(8f)

        val layoutParams = WindowManager.LayoutParams(
            sizePx, sizePx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(16f)
            y = dpToPx(200f)
        }

        val shape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(sizePx, sizePx)
            setColor(0xFF1565C0.toInt())
            // 外发光效果
            setStroke(dpToPx(2f), 0x80FFFFFF.toInt())
        }

        val imageView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_edit)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            background = shape

            setOnTouchListener(::onTouch)
        }

        try {
            windowManager.addView(imageView, layoutParams)
            bubbleView = imageView
            imageView.scaleX = 0f
            imageView.scaleY = 0f
            imageView.animate()
                .scaleX(1f).scaleY(1f)
                .setDuration(400)
                .start()
        } catch (_: Exception) {
            // 添加失败不停止服务，仅记录
            bubbleView = null
        }
    }

    private fun removeBubble() {
        bubbleView?.let { v ->
            try {
                v.animate().scaleX(0f).scaleY(0f).setDuration(200).withEndAction {
                    try { windowManager.removeView(v) } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
        bubbleView = null
    }

    // ========================================================================
    //  触摸事件 — 拖拽 + 点击
    // ========================================================================

    private fun onTouch(v: View, event: MotionEvent): Boolean {
        try {
            val params = v.layoutParams as WindowManager.LayoutParams

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isDragging = false
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    initialX = params.x.toFloat()
                    initialY = params.y.toFloat()
                    v.animate().scaleX(0.85f).scaleY(0.85f).setDuration(100).start()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (dx * dx + dy * dy > 100) {
                        isDragging = true
                        params.x = (initialX + dx).toInt()
                        params.y = (initialY + dy).toInt()
                        windowManager.updateViewLayout(v, params)
                    }
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()

                    if (!isDragging && !isTranslating) {
                        performTranslation()
                    }
                    snapToEdge(v)
                    return true
                }
            }
        } catch (_: Exception) {
            // 防止触摸事件中的任何异常导致悬浮球消失
        }
        return false
    }

    /**
     * 将悬浮球吸附到屏幕边缘
     */
    private fun snapToEdge(v: View) {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val params = v.layoutParams as WindowManager.LayoutParams
        val centerX = params.x + dpToPx(20f)

        val targetX = if (centerX < screenWidth / 2) dpToPx(4f)
                      else screenWidth - dpToPx(44f)

        if (params.x != targetX) {
            params.x = targetX
            try {
                windowManager.updateViewLayout(v, params)
            } catch (_: Exception) {}
        }
    }

    // ========================================================================
    //  翻译逻辑
    // ========================================================================

    /**
     * 翻译入口（点击悬浮球）。
     *
     * App 在后台时有两道系统限制，之前的实现两道都踩中了，所以「退到后台后点悬浮球没反应」：
     * 1. Android 10+ 只有拥有窗口焦点的 App 才能读剪贴板。悬浮球是 FLAG_NOT_FOCUSABLE，
     *    直接读剪贴板拿到的是 null —— 于是什么都不弹。
     *    → 现在先临时加一个可获得焦点的 1px 悬浮窗，拿到焦点后再读剪贴板（readClipboardWithFocus）。
     * 2. 后台 startActivity 会被 Android 10+ 及 ColorOS/MIUI 的「后台弹出界面」权限静默拦截。
     *    → 现在由服务直接显示系统悬浮窗（OverlayTranslationWindow），不再启动 Activity。
     */
    private fun performTranslation() {
        if (isTranslating) return
        isTranslating = true

        resolveTranslateText { text ->
            isTranslating = false
            when {
                text.isNullOrBlank() -> toast("没有获取到文字，请先选中文字并点「复制」")
                text.length > 5000 -> toast("文字太长（超过 5000 字），请缩短后再试")
                else -> showTranslation(text)
            }
        }
    }

    private fun showTranslation(text: String) {
        val window = overlayWindow ?: OverlayTranslationWindow(this).also { overlayWindow = it }
        try {
            window.show(text)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "悬浮窗显示失败，降级为 Activity", e)
            // 降级：App 在前台或系统允许后台弹出时仍可用
            try {
                startActivity(
                    Intent(this, ProcessTextActivity::class.java).apply {
                        putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    }
                )
            } catch (e2: Exception) {
                android.util.Log.e(TAG, "启动翻译界面失败", e2)
                toast("无法显示翻译，请检查「显示在其他应用上层」权限")
            }
        }
    }

    /**
     * 获取要翻译的文本（异步回调，始终在主线程回调）。
     * 优先从无障碍服务获取选中文本，其次读剪贴板。
     */
    private fun resolveTranslateText(callback: (String?) -> Unit) {
        // 1) 无障碍服务捕获的选中文本
        try {
            val accText = TranslateAccessibilityService.getSelectedText()
            if (!accText.isNullOrBlank()) {
                callback(accText.trim())
                return
            }
        } catch (_: Exception) { /* 忽略 */ }

        // 2) 剪贴板：App 在前台 / Android 9 及以下可以直接读到
        val direct = readClipboard()
        if (!direct.isNullOrBlank()) {
            callback(direct)
            return
        }

        // 3) 后台读不到：临时获取窗口焦点后再读
        readClipboardWithFocus(callback)
    }

    /** 读取剪贴板纯文本 — 遍历所有 clip 项拼接（链接会将文字拆成多项） */
    private fun readClipboard(): String? {
        return try {
            val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                ?: return null
            val sb = StringBuilder()
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).coerceToText(this)?.let { sb.append(it) }
            }
            sb.toString().trim().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Android 10+ 后台读剪贴板的标准做法：
     * 添加一个 1px、可获得焦点的透明悬浮窗，在 onWindowFocusChanged(true) 时读剪贴板，
     * 读完立即移除，把焦点还给原来的 App。超时也会兜底读一次。
     */
    private fun readClipboardWithFocus(callback: (String?) -> Unit) {
        removeClipboardProbe()

        var finished = false
        val handler = Handler(Looper.getMainLooper())
        lateinit var probe: View
        lateinit var timeout: Runnable

        fun finish() {
            if (finished) return
            finished = true
            handler.removeCallbacks(timeout)
            val text = readClipboard()   // 先读（此时仍持有焦点），再移除窗口
            if (clipboardProbe === probe) removeClipboardProbe()
            callback(text)
        }
        timeout = Runnable { finish() }

        probe = object : View(this) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (hasWindowFocus) post { finish() }
            }
        }

        val params = WindowManager.LayoutParams(
            1, 1,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // 注意：不能有 FLAG_NOT_FOCUSABLE，否则拿不到焦点
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            windowManager.addView(probe, params)
            clipboardProbe = probe
            handler.postDelayed(timeout, 800)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "添加剪贴板焦点窗口失败", e)
            finished = true
            callback(null)
        }
    }

    private fun removeClipboardProbe() {
        clipboardProbe?.let { v ->
            try { windowManager.removeView(v) } catch (_: Exception) {}
        }
        clipboardProbe = null
    }

    private fun toast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    // ========================================================================
    //  常驻通知
    // ========================================================================

    private fun createNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, App.CLIPBOARD_CHANNEL_ID)
            .setContentTitle("划词翻译")
            .setContentText("悬浮球运行中 — 选中文字后点击即可翻译")
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentIntent(pendingIntent)
            .setOngoing(true)                     // 常驻，不可滑动清除
            .setPriority(NotificationCompat.PRIORITY_MIN) // 最低优先级，不发出声音
            .setSilent(true)
            .build()
    }

    // ========================================================================
    //  工具方法
    // ========================================================================

    private fun dpToPx(dp: Float): Int =
        (dp * resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "FloatingBubble"
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingBubbleService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }
    }
}
