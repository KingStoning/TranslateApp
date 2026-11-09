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
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.core.app.NotificationCompat
import com.translate.app.App
import com.translate.app.MainActivity
import com.translate.app.ProcessTextActivity
import com.translate.app.R
import kotlinx.coroutines.*

/**
 * 悬浮球服务
 *
 * 在屏幕上显示一个可拖动的蓝色圆形悬浮球。
 * 点击后按优先级获取要翻译的文本：
 *   1. 无障碍服务捕获的选中文本（无需复制）
 *   2. 剪贴板内容（兜底方案）
 * 获取文本后调用百度翻译 API，结果以悬浮窗展示。
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
     * 翻译入口。
     *
     * 获取文本后启动 ProcessTextActivity 来处理翻译和显示。
     * 服务本身不创建任何弹窗，避免 Service Context 叠加窗口引发崩溃。
     */
    private fun performTranslation() {
        if (isTranslating) return
        isTranslating = true

        try {
            val text = resolveTranslateText()
            if (text.isNullOrBlank() || text.length > 5000 || text.length < 2) {
                isTranslating = false
                return
            }

            // 启动 Activity 处理翻译和显示
            // ProcessTextActivity 使用独立 taskAffinity，NEW_TASK 不会把主界面拉到前台；
            // CLEAR_TASK 保证每次都是全新的浮窗，不会复用上一次的旧文本。
            // （不再使用 LAUNCH_ADJACENT：它是分屏用的标志，部分 ROM 上会导致浮窗不弹出或进入分屏）
            startActivity(
                Intent(this, ProcessTextActivity::class.java).apply {
                    putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                }
            )
        } catch (_: Exception) {
            // 任何异常都不让悬浮球消失
        } finally {
            isTranslating = false
        }
    }

    /**
     * 获取要翻译的文本。
     * 优先从无障碍服务获取选中文本，失败则读剪贴板。
     */
    private fun resolveTranslateText(): String? {
        // 1) 无障碍服务捕获的选中文本
        try {
            val accText = TranslateAccessibilityService.getSelectedText()
            if (!accText.isNullOrBlank()) return accText
        } catch (_: Exception) { /* 忽略 */ }

        // 2) 剪贴板内容 — 遍历所有 clip 项拼接纯文本（链接会将文字拆成多项）
        try {
            val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip ?: return null
            val sb = StringBuilder()
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).text?.toString()?.let { sb.append(it) }
            }
            return sb.toString().trim().takeIf { it.isNotBlank() }
        } catch (_: Exception) { /* 忽略 */ }

        return null
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
        private const val NOTIFICATION_ID = 1002

        fun start(context: Context) {
            context.startForegroundService(Intent(context, FloatingBubbleService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingBubbleService::class.java))
        }
    }
}
