package com.translate.app.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.translate.app.data.settings.AutoTranslate
import com.translate.app.ui.overlay.OverlayTranslationWindow

/**
 * 无障碍服务
 *
 * 1. 选中英文自动翻译：监听全局「文本选中」事件，选中内容稳定 [AUTO_DELAY_MS] 后，
 *    如果是英文，直接弹出翻译卡片 —— 不需要复制，也不需要点悬浮球。
 *    卡片使用 TYPE_ACCESSIBILITY_OVERLAY，不需要「显示在其他应用上层」权限。
 * 2. 记录最近选中的文字，点击悬浮球时优先翻译它（无需复制）。
 *
 * 用户需要先在 系统设置 → 无障碍 → 已安装的应用 → 划词翻译 中开启此服务。
 *
 * 局限：依赖应用发出的无障碍选中事件。原生文本控件都会发；
 * 部分自绘界面 / 网页内容不发，这时仍可用「复制 + 悬浮球」或系统菜单里的「翻译」。
 */
class TranslateAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var overlay: OverlayTranslationWindow? = null
    private var pendingAuto: Runnable? = null

    /** 已自动弹出过的选中内容；选区清空后重置，避免同一选区反复弹出 */
    private var lastAutoText: String? = null

    /** 弹出卡片时所在的应用；切换到别的应用时自动关闭卡片 */
    private var overlayPackage: CharSequence? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        val config = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        serviceInfo = config
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        try {
            when (event.eventType) {
                AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                    val selected = captureSelectedText(event)
                    handleAutoTranslate(event, selected)
                }
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                    // 部分浏览器可能只发 TEXT_CHANGED，也尝试捕获（仅供悬浮球使用，不自动弹出）
                    if (lastSelectedText == null) captureSelectedText(event)
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    // 切换到其他应用：关闭卡片
                    val pkg = event.packageName
                    if (overlay?.isShowing == true && pkg != null && pkg != packageName &&
                        overlayPackage != null && pkg != overlayPackage
                    ) {
                        dismissOverlay()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "处理无障碍事件失败", e)
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        cancelPending()
        dismissOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    // ========================================================================
    //  选中英文自动翻译
    // ========================================================================

    private fun handleAutoTranslate(event: AccessibilityEvent, selected: String?) {
        cancelPending()

        val text = selected?.trim()
        if (text.isNullOrEmpty()) {
            lastAutoText = null // 选区清空：下次选中同样的内容也会再弹
            return
        }
        if (!AutoTranslate.isEnabled(this)) return
        if (event.packageName == packageName) return // 本应用内（如翻译卡片、主界面）
        if (event.isPassword) return
        if (text == lastAutoText) return
        if (!AutoTranslate.isEnglish(text)) return

        val source = event.source
        // 输入框里选中文字多半是在编辑（剪切、删除），不打扰
        if (source?.isEditable == true) return
        val atBottom = source?.let { selectionInUpperHalf(it, event) } ?: false
        val pkg = event.packageName

        // 拖动选区手柄时会连续触发事件，等选区稳定后再弹出
        val task = Runnable {
            pendingAuto = null
            if (lastSelectedText?.trim() != text) return@Runnable
            lastAutoText = text
            showOverlay(text, atBottom, pkg)
        }
        pendingAuto = task
        handler.postDelayed(task, AUTO_DELAY_MS)
    }

    private fun showOverlay(text: String, atBottom: Boolean, pkg: CharSequence?) {
        val window = overlay ?: OverlayTranslationWindow(this, accessibilityOverlay = true)
            .also { overlay = it }
        try {
            window.show(text, atBottom)
            overlayPackage = pkg
        } catch (e: Exception) {
            Log.w(TAG, "显示自动翻译卡片失败", e)
        }
    }

    private fun dismissOverlay() {
        overlay?.dismiss()
        overlayPackage = null
    }

    private fun cancelPending() {
        pendingAuto?.let { handler.removeCallbacks(it) }
        pendingAuto = null
    }

    /**
     * 选中的文字是否在屏幕上半部分（是则卡片放到底部，避免挡住选中的文字和复制菜单）。
     * Android 8+ 优先取选区第一个字符的精确位置，取不到时用整个控件的位置估计。
     */
    private fun selectionInUpperHalf(node: AccessibilityNodeInfo, event: AccessibilityEvent): Boolean {
        val screenHeight = resources.displayMetrics.heightPixels
        var top: Float? = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val start = when {
                    node.textSelectionStart >= 0 -> node.textSelectionStart
                    event.fromIndex >= 0 -> event.fromIndex
                    else -> -1
                }
                val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
                if (start >= 0 && node.availableExtraData.contains(key)) {
                    val args = Bundle().apply {
                        putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, start)
                        putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, 1)
                    }
                    if (node.refreshWithExtraData(key, args)) {
                        @Suppress("DEPRECATION")
                        val rects = node.extras.getParcelableArray(key)
                        (rects?.firstOrNull() as? RectF)?.let { top = it.top }
                    }
                }
            } catch (_: Exception) { /* 不支持就用控件位置 */ }
        }

        if (top == null) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (r.height() in 1 until screenHeight) top = r.exactCenterY()
        }
        return (top ?: return false) < screenHeight / 2f
    }

    // ========================================================================
    //  选中文本捕获
    // ========================================================================

    /**
     * 从事件中提取选中的文本，并更新 [lastSelectedText]。
     * @return 当前选中的文字；选区为空（只是光标）时返回 null 并清空缓存
     */
    private fun captureSelectedText(event: AccessibilityEvent): String? {
        // 选区起止相同 = 没有选中任何文字（取消选择 / 移动光标）
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED &&
            event.fromIndex >= 0 && event.fromIndex == event.toIndex
        ) {
            clearSelection()
            return null
        }

        // 方法1：从事件的文本列表中获取（部分应用会在这里放选中的文本）
        if (event.text.isNotEmpty()) {
            val fullText = event.text.joinToString("")
            if (fullText.isNotBlank()) {
                val fromIndex = event.fromIndex.coerceAtLeast(0)
                val toIndex = event.toIndex.coerceAtLeast(0)
                if (toIndex > fromIndex && toIndex <= fullText.length) {
                    return remember(fullText.substring(fromIndex, toIndex))
                }
            }
        }

        // 方法2：通过事件来源节点查找当前选中文本
        val source = event.source ?: return null
        val selectedText = extractSelectedTextFromNode(source)
        return if (!selectedText.isNullOrBlank()) remember(selectedText) else null
    }

    private fun remember(text: String): String {
        lastSelectedText = text
        lastSelectedAt = System.currentTimeMillis()
        return text
    }

    private fun clearSelection() {
        lastSelectedText = null
        lastSelectedAt = 0L
    }

    /**
     * 从节点中提取选中文本（递归查找）
     */
    private fun extractSelectedTextFromNode(node: AccessibilityNodeInfo, depth: Int = 0): String? {
        if (node.isPassword || depth > 30) return null

        val text = node.text?.toString()
        val selectionStart = node.textSelectionStart
        val selectionEnd = node.textSelectionEnd

        if (text != null && selectionStart >= 0 && selectionEnd > selectionStart && selectionEnd <= text.length) {
            return text.substring(selectionStart, selectionEnd)
        }

        // 递归查找子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = extractSelectedTextFromNode(child, depth + 1)
            if (!result.isNullOrBlank()) return result
        }

        return null
    }

    /**
     * 主动获取当前选中文本（由外部调用）
     */
    fun fetchCurrentSelectedText(): String? {
        val root = rootInActiveWindow ?: return null
        return extractSelectedTextFromNode(root)
    }

    companion object {
        private const val TAG = "TranslateA11y"

        /** 选区稳定多久后自动弹出翻译 */
        private const val AUTO_DELAY_MS = 600L

        /** 选中文字的缓存有效期：超过后点悬浮球改为读剪贴板，避免一直翻译很久以前选中的内容 */
        private const val SELECTION_TTL_MS = 60_000L

        /** 最近一次捕获的选中文本 */
        @Volatile
        var lastSelectedText: String? = null
            private set

        @Volatile
        private var lastSelectedAt = 0L

        @Volatile
        private var instance: TranslateAccessibilityService? = null

        /** 无障碍服务是否正在运行 */
        val isRunning: Boolean get() = instance != null

        /**
         * 获取当前选中的文本
         * 先尝试缓存（60 秒内有效），再尝试实时抓取
         */
        fun getSelectedText(): String? {
            val cached = lastSelectedText
            if (!cached.isNullOrBlank() &&
                System.currentTimeMillis() - lastSelectedAt < SELECTION_TTL_MS
            ) return cached

            return try {
                instance?.fetchCurrentSelectedText()
            } catch (_: Exception) {
                null
            }
        }
    }
}
