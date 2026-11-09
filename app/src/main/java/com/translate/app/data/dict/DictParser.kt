package com.translate.app.data.dict

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.translate.app.data.model.EnglishDefinition
import com.translate.app.data.model.WordDetail
import com.translate.app.data.model.WordExchange
import com.translate.app.data.model.WordPart

/**
 * 解析百度翻译「词典资源」(trans_result[].dict)。
 *
 * 英译中典型结构（dict 可能是对象，也可能是需要二次解析的 JSON 字符串）：
 * {
 *   "lang": "1",
 *   "word_result": {
 *     "simple_means": {
 *       "word_name": "apple",
 *       "word_means": ["苹果", ...],
 *       "exchange": { "word_pl": ["apples"], ... },
 *       "tags": { "core": ["高考", "考研"], "other": [""] },
 *       "symbols": [{
 *         "ph_en": "ˈæpl", "ph_am": "ˈæpl", "ph_other": "",
 *         "parts": [{ "part": "n.", "means": ["苹果", "苹果树"] }]
 *       }]
 *     },
 *     "edict": { "item": [{ "pos": "noun", "tr_group": [{ "tr": [...], "example": [...], "similar_word": [...] }] }] }
 *   }
 * }
 * 中译英时 symbols 里是拼音（word_symbol），parts[].means 是对象数组（text / word_mean）。
 *
 * 字段在不同文档、不同词条中差异较大，这里全部按「可能缺失、可能类型不同」处理，
 * 解析失败返回 null，绝不影响普通翻译结果。
 */
object DictParser {

    private const val TAG = "DictParser"

    private val EXCHANGE_LABELS = linkedMapOf(
        "word_pl" to "复数",
        "word_third" to "第三人称单数",
        "word_past" to "过去式",
        "word_done" to "过去分词",
        "word_ing" to "现在分词",
        "word_er" to "比较级",
        "word_est" to "最高级",
        "word_proto" to "原形"
    )

    fun parse(dict: JsonElement?, fallbackWord: String): WordDetail? {
        return try {
            val root = toObject(dict) ?: return null
            val wordResult = root.obj("word_result") ?: root
            val simple = wordResult.obj("simple_means")

            val word = simple?.str("word_name")?.takeIf { it.isNotBlank() } ?: fallbackWord

            var phUk: String? = null
            var phUs: String? = null
            var phOther: String? = null
            val parts = mutableListOf<WordPart>()

            simple?.arr("symbols")?.forEach { symEl ->
                val sym = symEl.asObjOrNull() ?: return@forEach
                if (phUk == null) phUk = sym.str("ph_en")?.takeIf { it.isNotBlank() }
                if (phUs == null) phUs = sym.str("ph_am")?.takeIf { it.isNotBlank() }
                if (phOther == null) {
                    phOther = (sym.str("ph_other") ?: sym.str("word_symbol"))?.takeIf { it.isNotBlank() }
                }
                sym.arr("parts")?.forEach { partEl ->
                    parsePart(partEl)?.let { parts += it }
                }
            }

            // 没有分词性的释义时，用 word_means 兜底
            if (parts.isEmpty()) {
                val means = simple?.arr("word_means")?.strings().orEmpty()
                if (means.isNotEmpty()) parts += WordPart("", means)
            }

            val exchanges = mutableListOf<WordExchange>()
            simple?.obj("exchange")?.let { ex ->
                for ((key, label) in EXCHANGE_LABELS) {
                    val forms = ex.get(key).stringsOrSingle().filter { it.isNotBlank() }
                    if (forms.isNotEmpty()) exchanges += WordExchange(label, forms)
                }
            }

            val tags = simple?.obj("tags")?.let { t ->
                (t.get("core").stringsOrSingle() + t.get("other").stringsOrSingle())
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .distinct()
            }.orEmpty()

            val englishDefs = parseEdict(wordResult.get("edict"))

            WordDetail(
                word = word,
                phoneticUk = phUk,
                phoneticUs = phUs,
                phoneticOther = phOther,
                parts = parts,
                exchanges = exchanges,
                tags = tags,
                englishDefinitions = englishDefs
            ).takeIf { it.hasContent }
        } catch (e: Exception) {
            Log.w(TAG, "解析词典资源失败", e)
            null
        }
    }

    private fun parsePart(el: JsonElement): WordPart? {
        val obj = el.asObjOrNull() ?: return null
        val part = (obj.str("part") ?: obj.str("part_name")).orEmpty().trim()
        val means = mutableListOf<String>()
        obj.arr("means")?.forEach { m ->
            when {
                m.isJsonPrimitive -> m.asString.trim().takeIf { it.isNotEmpty() }?.let { means += it }
                m.isJsonObject -> {
                    val mo = m.asJsonObject
                    // 中译英：{ "text": "apple", "word_mean": "苹果", "means": ["apple", ...] }
                    val text = mo.str("text") ?: mo.str("word_mean")
                    if (!text.isNullOrBlank()) means += text.trim()
                }
            }
        }
        val cleaned = means.map { it.trimEnd(';', '；') }.filter { it.isNotEmpty() }.distinct()
        return if (cleaned.isEmpty()) null else WordPart(part, cleaned)
    }

    private fun parseEdict(el: JsonElement?): List<EnglishDefinition> {
        val edict = toObject(el) ?: return emptyList()
        val result = mutableListOf<EnglishDefinition>()
        edict.arr("item")?.forEach { itemEl ->
            val item = itemEl.asObjOrNull() ?: return@forEach
            val pos = item.str("pos").orEmpty()
            val defs = mutableListOf<String>()
            val examples = mutableListOf<String>()
            val similar = mutableListOf<String>()
            item.arr("tr_group")?.forEach { gEl ->
                val g = gEl.asObjOrNull() ?: return@forEach
                defs += g.get("tr").stringsOrSingle()
                examples += g.get("example").stringsOrSingle()
                similar += g.get("similar_word").stringsOrSingle()
            }
            val d = defs.filter { it.isNotBlank() }
            if (d.isNotEmpty()) {
                result += EnglishDefinition(
                    pos = pos,
                    definitions = d,
                    examples = examples.filter { it.isNotBlank() },
                    similarWords = similar.filter { it.isNotBlank() }.distinct()
                )
            }
        }
        return result
    }

    // ---------------------------------------------------------------- helpers

    /** dict 可能是对象，也可能是 JSON 字符串 */
    private fun toObject(el: JsonElement?): JsonObject? {
        if (el == null || el.isJsonNull) return null
        if (el.isJsonObject) return el.asJsonObject
        if (el.isJsonPrimitive && el.asJsonPrimitive.isString) {
            val s = el.asString.trim()
            if (s.isEmpty() || !s.startsWith("{")) return null
            return runCatching { JsonParser.parseString(s).asJsonObject }.getOrNull()
        }
        return null
    }

    private fun JsonElement.asObjOrNull(): JsonObject? = if (isJsonObject) asJsonObject else null

    private fun JsonObject.obj(key: String): JsonObject? = toObject(get(key))

    private fun JsonObject.arr(key: String): JsonArray? =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray

    private fun JsonObject.str(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonArray.strings(): List<String> =
        mapNotNull { if (it.isJsonPrimitive) it.asString.trim() else null }.filter { it.isNotEmpty() }

    /** 兼容 ["a","b"] 和 "a" 两种写法 */
    private fun JsonElement?.stringsOrSingle(): List<String> = when {
        this == null || isJsonNull -> emptyList()
        isJsonArray -> asJsonArray.strings()
        isJsonPrimitive -> listOf(asString.trim()).filter { it.isNotEmpty() }
        else -> emptyList()
    }
}
