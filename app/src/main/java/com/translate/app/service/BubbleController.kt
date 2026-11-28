package com.translate.app.service

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import com.translate.app.ProcessTextActivity
import com.translate.app.data.settings.BubblePrefs
import com.translate.app.ui.overlay.OverlayTranslationWindow

/**
 * 悬浮球（界面 + 拖动 + 点击翻译），可由两种宿主显示：
 *
 * - [TranslateAccessibilityService]（首选）：窗口类型 TYPE_ACCESSIBILITY_OVERLAY。
 *   只要无障碍服务开着，系统就会一直保持它运行、进程被杀后自动重新绑定，
 *   悬浮球也就「一直都在」；不需要悬浮窗权限，也没有常驻通知。
 * - [FloatingBubbleService]（无障碍未开启时的兜底）：前台服务 + TYPE_APPLICATION_OVERLAY。
 *
 * @param host 宿主 Service。无障碍模式下必须是无障碍服务本身（窗口要挂在它的 token 上）
 */
class BubbleController(
    private val host: Context,
    private val accessibilityOverlay: Boolean
) {
    private val appContext = host.applicationContext
    private val windowManager = host.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val handler = Handler(Looper.getMainLooper())

    private var bubbleView: ImageView? = null
    private var overlayWindow: OverlayTranslationWindow? = null
    private var clipboardProbe: View? = null
    private var probeWindowManager: WindowManager? = null
    private var isTranslating = false

    // 拖动状态
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialX = 0
    private var initialY = 0
    private var isDragging = false

    val isShowing: Boolean get() = bubbleView != null

    private val bubbleWindowType: Int
        get() = when {
            accessibilityOverlay -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ->
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        }

    // ========================================================================
    //  悬浮球 UI
    // ========================================================================

    /** 显示悬浮球；失败返回 false */
    fun show(): Boolean {
        if (bubbleView != null) return true

        val sizePx = dpToPx(40f)
        val paddingPx = dpToPx(8f)
        val (savedX, savedY) = BubblePrefs.position(appContext, dpToPx(4f), dpToPx(200f))

        val layoutParams = WindowManager.LayoutParams(
            sizePx, sizePx,
            bubbleWindowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = savedX.coerceIn(0, (screenWidth() - sizePx).coerceAtLeast(0))
            y = savedY.coerceIn(0, (screenHeight() - sizePx).coerceAtLeast(0))
        }

        val shape = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(sizePx, sizePx)
            setColor(0xFF1565C0.toInt())
            setStroke(dpToPx(2f), 0x80FFFFFF.toInt())
        }

        val imageView = ImageView(host).apply {
            setImageResource(android.R.drawable.ic_menu_edit)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            background = shape
            contentDescription = "划词翻译悬浮球"
            setOnTouchListener(::onTouch)
        }

        return try {
            windowManager.addView(imageView, layoutParams)
            bubbleView = imageView
            imageView.scaleX = 0f
            imageView.scaleY = 0f
            imageView.animate().scaleX(1f).scaleY(1f).setDuration(300).start()
            true
        } catch (e: Exception) {
            Log.w(TAG, "显示悬浮球失败", e)
            bubbleView = null
            false
        }
    }

    /** 移除悬浮球和翻译卡片 */
    fun hide() {
        handler.removeCallbacksAndMessages(null)
        removeClipboardProbe()
        overlayWindow?.dismiss()
        overlayWindow = null
        bubbleView?.let { v ->
            try { windowManager.removeView(v) } catch (_: Exception) {}
        }
        bubbleView = null
        isTranslating = false
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
                    initialX = params.x
                    initialY = params.y
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
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
                    if (event.action == MotionEvent.ACTION_UP && !isDragging && !isTranslating) {
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

    /** 吸附到屏幕左右边缘，并记住位置（重启后恢复） */
    private fun snapToEdge(v: View) {
        val params = v.layoutParams as WindowManager.LayoutParams
        val centerX = params.x + dpToPx(20f)
        params.x = if (centerX < screenWidth() / 2) dpToPx(4f) else screenWidth() - dpToPx(44f)
        params.y = params.y.coerceIn(0, (screenHeight() - dpToPx(40f)).coerceAtLeast(0))
        try { windowManager.updateViewLayout(v, params) } catch (_: Exception) {}
        BubblePrefs.savePosition(appContext, params.x, params.y)
    }

    // ========================================================================
    //  翻译逻辑
    // ========================================================================

    /**
     * 点击悬浮球。
     *
     * 后台时的两道系统限制：
     * 1. Android 10+ 只有拥有窗口焦点的 App 才能读剪贴板 → 临时加一个可获得焦点的 1px 窗口再读
     * 2. 后台 startActivity 会被拦截 → 直接显示悬浮窗（OverlayTranslationWindow）
     */
    private fun performTranslation() {
        if (isTranslating) return
        isTranslating = true

        resolveTranslateText { text ->
            isTranslating = false
            when {
                text.isNullOrBlank() -> toast(
                    if (TranslateAccessibilityService.isRunning)
                        "没有获取到文字：请先选中文字（保持复制菜单显示）再点悬浮球"
                    else
                        "没有获取到文字，请先选中文字并点「复制」\n开启无障碍服务后可免复制"
                )
                text.length > 5000 -> toast("文字太长（超过 5000 字），请缩短后再试")
                else -> showTranslation(text)
            }
        }
    }

    private fun showTranslation(text: String) {
        val window = overlayWindow
            ?: OverlayTranslationWindow(host, accessibilityOverlay).also { overlayWindow = it }
        try {
            window.show(text)
        } catch (e: Exception) {
            Log.w(TAG, "悬浮窗显示失败，降级为 Activity", e)
            try {
                appContext.startActivity(
                    Intent(appContext, ProcessTextActivity::class.java).apply {
                        putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    }
                )
            } catch (e2: Exception) {
                Log.e(TAG, "启动翻译界面失败", e2)
                toast("无法显示翻译")
            }
        }
    }

    /**
     * 获取要翻译的文本（主线程回调）：
     * 1. 无障碍服务捕获 / 实时读取到的选中文字（普通 App）
     * 2. 读不到选中文字时（浏览器、网页、部分 App）：自动点击选中菜单里的「复制」，再读剪贴板
     * 3. 用户自己复制的剪贴板内容
     */
    private fun resolveTranslateText(callback: (String?) -> Unit) {
        try {
            val accText = TranslateAccessibilityService.getSelectedText()
            if (!accText.isNullOrBlank()) {
                callback(accText.trim())
                return
            }
        } catch (_: Exception) { /* 忽略 */ }

        if (TranslateAccessibilityService.copySelectionViaToolbar()) {
            handler.postDelayed({ readClipboardWithFocus(callback) }, COPY_SETTLE_MS)
            return
        }

        val direct = readClipboard()
        if (!direct.isNullOrBlank()) {
            callback(direct)
            return
        }
        readClipboardWithFocus(callback)
    }

    /** 读取剪贴板纯文本 — 遍历所有 clip 项拼接（链接会将文字拆成多项） */
    private fun readClipboard(): String? {
        return try {
            val clip = (appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .primaryClip ?: return null
            val sb = StringBuilder()
            for (i in 0 until clip.itemCount) {
                clip.getItemAt(i).coerceToText(appContext)?.let { sb.append(it) }
            }
            sb.toString().trim().takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Android 10+ 后台读剪贴板：添加一个 1px、可获得焦点的透明窗口，
     * 在 onWindowFocusChanged(true) 时读剪贴板，读完立即移除。超时也兜底读一次。
     *
     * 有悬浮窗权限时用普通悬浮窗（已在真机验证可行）；否则用无障碍悬浮窗。
     */
    private fun readClipboardWithFocus(callback: (String?) -> Unit) {
        removeClipboardProbe()

        val useAppOverlay = !accessibilityOverlay || canDrawOverlays()
        val wm = if (useAppOverlay && accessibilityOverlay)
            appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        else windowManager
        val type = if (useAppOverlay) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        } else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

        var finished = false
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

        probe = object : View(appContext) {
            override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                super.onWindowFocusChanged(hasWindowFocus)
                if (hasWindowFocus) post { finish() }
            }
        }

        val params = WindowManager.LayoutParams(
            1, 1, type,
            // 注意：不能有 FLAG_NOT_FOCUSABLE，否则拿不到焦点
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSPARENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            wm.addView(probe, params)
            clipboardProbe = probe
            probeWindowManager = wm
            handler.postDelayed(timeout, 800)
        } catch (e: Exception) {
            Log.w(TAG, "添加剪贴板焦点窗口失败", e)
            finished = true
            callback(readClipboard())
        }
    }

    private fun removeClipboardProbe() {
        clipboardProbe?.let { v ->
            try { (probeWindowManager ?: windowManager).removeView(v) } catch (_: Exception) {}
        }
        clipboardProbe = null
        probeWindowManager = null
    }

    // ========================================================================
    //  工具方法
    // ========================================================================

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(appContext)

    private fun toast(msg: String) {
        try { Toast.makeText(appContext, msg, Toast.LENGTH_SHORT).show() } catch (_: Exception) {}
    }

    private fun screenWidth() = host.resources.displayMetrics.widthPixels
    private fun screenHeight() = host.resources.displayMetrics.heightPixels

    private fun dpToPx(dp: Float): Int = (dp * host.resources.displayMetrics.density).toInt()

    companion object {
        private const val TAG = "Bubble"
        /** 点击「复制」后等待剪贴板写入的时间 */
        private const val COPY_SETTLE_MS = 250L
    }
}
