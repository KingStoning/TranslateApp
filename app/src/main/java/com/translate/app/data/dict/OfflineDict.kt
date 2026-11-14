package com.translate.app.data.dict

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.translate.app.data.model.WordDetail
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * 内置离线英汉词库（ECDICT，MIT License，https://github.com/skywind3000/ECDICT）。
 *
 * assets/ecdict.db.gzip 由 tools/build_ecdict.py 生成；第一次使用时解压到 App 私有目录。
 * 查词完全离线，不消耗百度翻译额度，也不需要开通任何词典资源。
 */
object OfflineDict {

    private const val TAG = "OfflineDict"
    /**
     * 注意扩展名不能是 .gz：Android 打包工具会把 assets 里的 .gz 文件自动解压并去掉扩展名，
     * 运行时按 .gz 打开会找不到文件。
     */
    private const val ASSET = "ecdict.db.gzip"
    /** 词库更新时 +1，会重新解压并删除旧文件 */
    private const val DB_VERSION = 1
    private const val DB_NAME = "ecdict-v$DB_VERSION.db"

    @Volatile
    private var db: SQLiteDatabase? = null
    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** 在后台线程预先解压 / 打开词库，避免第一次查词等待 */
    fun warmUp() {
        Thread({ runCatching { open() } }, "OfflineDict-warmup").start()
    }

    @Synchronized
    private fun open(): SQLiteDatabase? {
        db?.let { return it }
        if (!::appContext.isInitialized) return null
        return try {
            val dir = File(appContext.noBackupFilesDir, "dict").apply { mkdirs() }
            val file = File(dir, DB_NAME)
            if (!file.exists()) {
                dir.listFiles()?.forEach { it.delete() } // 清理旧版本 / 未完成的解压
                val tmp = File(dir, "$DB_NAME.tmp")
                appContext.assets.open(ASSET).use { input ->
                    GZIPInputStream(input, 64 * 1024).use { gz ->
                        tmp.outputStream().use { out -> gz.copyTo(out, 64 * 1024) }
                    }
                }
                if (!tmp.renameTo(file)) error("rename failed")
            }
            SQLiteDatabase.openDatabase(
                file.path, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            ).also { db = it }
        } catch (e: Exception) {
            Log.e(TAG, "打开离线词库失败", e)
            null
        }
    }

    /**
     * 查词（需在后台线程调用）。
     * 1. 直接查（忽略大小写）
     * 2. 查不到时查变形索引：如 tomatoes → tomato（复数）
     * 查到的是变形词条（如 booked）时，附带「是 book 的过去式」说明。
     */
    fun lookup(raw: String): WordDetail? {
        val word = raw.trim()
            .trim('.', ',', '!', '?', ';', ':', '"', '“', '”', '(', ')')
            .replace('’', '\'')
        if (word.isEmpty()) return null
        val database = open() ?: return null
        return try {
            findEntry(database, word)?.let { return it }

            // 变形索引
            database.rawQuery("SELECT base, kind FROM forms WHERE form = ?", arrayOf(word)).use { c ->
                if (c.moveToFirst()) {
                    val base = c.getString(0)
                    val kinds = EcdictParser.describeKinds(c.getString(1))
                    return findEntry(database, base, "$word 是 $base 的$kinds")
                }
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "查词失败: $word", e)
            null
        }
    }

    private fun findEntry(
        database: SQLiteDatabase,
        word: String,
        noteOverride: String? = null
    ): WordDetail? {
        database.rawQuery(
            "SELECT word, phonetic, translation, definition, tag, exchange, collins, oxford " +
                    "FROM dict WHERE word = ?",
            arrayOf(word)
        ).use { c ->
            if (!c.moveToFirst()) return null
            val entryWord = c.getString(0)
            val exchange = c.getString(5).orEmpty()
            val ex = EcdictParser.parseExchange(exchange)
            // 变形词条自带原形信息：0 = 原形，1 = 变形类型
            val lemmaNote = ex["0"]
                ?.takeIf { !it.equals(entryWord, ignoreCase = true) }
                ?.let { base ->
                    val kinds = EcdictParser.describeKinds(ex["1"].orEmpty())
                    if (kinds.isNotEmpty()) "$entryWord 是 $base 的$kinds" else null
                }
            return EcdictParser.toWordDetail(
                word = entryWord,
                phonetic = c.getString(1),
                translation = c.getString(2),
                definition = c.getString(3),
                tag = c.getString(4),
                exchange = exchange,
                collins = c.getInt(6),
                oxford = c.getInt(7) == 1,
                note = noteOverride ?: lemmaNote
            )
        }
    }
}
