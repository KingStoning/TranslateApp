package com.translate.app.ui.overlay

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.translate.app.TranslationCard
import com.translate.app.tts.Speaker
import com.translate.app.ui.theme.TranslateAppTheme

/**
 * 由悬浮球服务直接显示的翻译悬浮窗（TYPE_APPLICATION_OVERLAY）。
 *
 * 为什么不用 startActivity 打开 ProcessTextActivity：
 * App 退到后台后，Android 10+ 限制「后台启动 Activity」，
 * ColorOS / OxygenOS / MIUI 等还有单独的「后台弹出界面」权限，
 * 会让 startActivity 被静默拦截 —— 表现就是点悬浮球没反应。
 * 悬浮窗只依赖已经授予的「显示在其他应用上层」权限，后台也能弹出。
 *
 * 窗口不抢焦点、只占顶部卡片区域，点卡片外任意位置（ACTION_OUTSIDE）或按 ✕ 关闭，
 * 不影响下面的 App 继续操作。
 */
class OverlayTranslationWindow(context: Context) {

    private val appContext = context.applicationContext
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var root: FrameLayout? = null
    private var owner: OverlayOwner? = null
    private var currentText by mutableStateOf("")

    val isShowing: Boolean get() = root != null

    /**
     * 显示（或刷新）翻译悬浮窗。
     * @throws Exception 添加窗口失败（如没有悬浮窗权限）时抛出，调用方可降级处理
     */
    fun show(text: String) {
        currentText = text
        if (root != null) return  // 已显示：只刷新文本，卡片内部会重新翻译

        val lifecycleOwner = OverlayOwner().also { it.start() }

        val composeView = ComposeView(appContext).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                TranslateAppTheme(dynamicColor = false) {
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        TranslationCard(
                            text = currentText,
                            onDismiss = { dismiss() },
                            onCopy = { copyToClipboard(it) }
                        )
                    }
                }
            }
        }

        val container = object : FrameLayout(appContext) {
            override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    dismiss()
                    return true
                }
                return super.dispatchTouchEvent(ev)
            }
        }
        container.setViewTreeLifecycleOwner(lifecycleOwner)
        container.setViewTreeSavedStateRegistryOwner(lifecycleOwner)
        container.addView(composeView)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (40 * appContext.resources.displayMetrics.density).toInt()
        }

        try {
            windowManager.addView(container, params)
        } catch (e: Exception) {
            lifecycleOwner.destroy()
            throw e
        }
        root = container
        owner = lifecycleOwner
    }

    fun dismiss() {
        val v = root ?: return
        root = null
        Speaker.stop()
        try { windowManager.removeView(v) } catch (_: Exception) {}
        owner?.destroy()
        owner = null
    }

    private fun copyToClipboard(text: String) {
        val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("translation", text))
        // Android 13+ 系统会自己提示「已复制」
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(appContext, "已复制译文", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * ComposeView 需要 LifecycleOwner + SavedStateRegistryOwner 才能工作；
     * Service 里没有 Activity，这里手动提供一个（这正是之前 Service 弹窗崩溃的原因）。
     */
    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedStateController = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry
            get() = savedStateController.savedStateRegistry

        fun start() {
            savedStateController.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun destroy() {
            if (registry.currentState != Lifecycle.State.INITIALIZED) {
                registry.currentState = Lifecycle.State.DESTROYED
            }
        }
    }
}
