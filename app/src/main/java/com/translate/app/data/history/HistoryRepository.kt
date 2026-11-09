package com.translate.app.data.history

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.translate.app.data.model.HistoryItem
import com.translate.app.data.model.TranslationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * 翻译历史（本地 JSON 文件，最多保留 [MAX_ITEMS] 条）。
 *
 * 浮窗 Activity、悬浮球悬浮窗、主界面都在同一进程，共用这一个单例；
 * 列表以 StateFlow 暴露，任意入口新增的记录主界面会实时刷新。
 */
object HistoryRepository {

    private const val TAG = "History"
    private const val FILE_NAME = "translate_history.json"
    private const val MAX_ITEMS = 500

    private val gson = Gson()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val _items = MutableStateFlow<List<HistoryItem>>(emptyList())
    val items: StateFlow<List<HistoryItem>> = _items.asStateFlow()

    @Volatile
    private var file: File? = null

    /** 在 Application.onCreate 中调用一次 */
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.applicationContext.filesDir, FILE_NAME)
        file = f
        ioScope.launch {
            val loaded = try {
                if (f.exists()) {
                    // 用数组类型而不是 TypeToken：R8 full mode 下匿名 TypeToken 可能丢失泛型签名导致崩溃
                    gson.fromJson(f.readText(), Array<HistoryItem?>::class.java).orEmpty()
                        .filterNotNull()
                        // Gson 不走 Kotlin 构造函数，字段可能为 null，过滤掉损坏的条目
                        .filter { (it.originalText as String?) != null && (it.translatedText as String?) != null }
                } else emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "读取历史失败", e)
                emptyList()
            }
            // 加载期间如果已经有新记录写入，合并到前面
            _items.update { current -> (current + loaded).distinctBy { key(it.originalText) } }
        }
    }

    /** 记录一次成功的翻译；相同原文会累加次数并移到最前 */
    fun add(result: TranslationResult) {
        val text = result.originalText.trim()
        if (text.isEmpty()) return
        val now = System.currentTimeMillis()
        _items.update { list ->
            val k = key(text)
            val existing = list.firstOrNull { key(it.originalText) == k }
            val item = HistoryItem(
                id = existing?.id ?: now,
                originalText = text,
                translatedText = result.translatedText,
                fromLanguage = result.fromLanguage,
                toLanguage = result.toLanguage,
                timestamp = now,
                count = (existing?.count ?: 0) + 1,
                wordDetail = result.wordDetail ?: existing?.wordDetail
            )
            (listOf(item) + list.filter { key(it.originalText) != k }).take(MAX_ITEMS)
        }
        persist()
    }

    fun delete(id: Long) {
        _items.update { list -> list.filter { it.id != id } }
        persist()
    }

    fun clear() {
        _items.value = emptyList()
        persist()
    }

    private fun key(text: String) = text.trim().lowercase()

    private fun persist() {
        val f = file ?: return
        val snapshot = _items.value
        ioScope.launch {
            try {
                val tmp = File(f.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(gson.toJson(snapshot))
                if (!tmp.renameTo(f)) {
                    f.writeText(gson.toJson(snapshot))
                    tmp.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "保存历史失败", e)
            }
        }
    }
}
