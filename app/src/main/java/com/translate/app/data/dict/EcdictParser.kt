package com.translate.app.data.dict

import com.translate.app.data.model.EnglishDefinition
import com.translate.app.data.model.WordDetail
import com.translate.app.data.model.WordExchange
import com.translate.app.data.model.WordPart

/**
 * 把 ECDICT 词条的各字段转换成 [WordDetail]（纯函数，便于单元测试）。
 *
 * ECDICT 字段示例（fine）：
 *   translation: "n. 罚款, 罚金, 晴天\na. 好的, 晴朗的\nvt. 罚款, 精炼\n[计] 精细"
 *   definition:  "n. money extracted as a penalty\ns. minutely precise ..."
 *   tag:         "zk gk toefl ielts"
 *   exchange:    "t:finest/d:fined/s:fines/r:finer/p:fined/3:fines/i:fining"
 *   变形词条 booked 的 exchange 里还有 "0:book/1:dp"，表示原形 book，是过去式和过去分词
 */
object EcdictParser {

    private val POS_LINE = Regex("^([a-z]{1,6}\\.)\\s*(.*)$")
    private val DOMAIN_LINE = Regex("^(\\[[^\\]]{1,8}])\\s*(.*)$")

    private val EXCHANGE_LABELS = linkedMapOf(
        "s" to "复数",
        "3" to "第三人称单数",
        "p" to "过去式",
        "d" to "过去分词",
        "i" to "现在分词",
        "r" to "比较级",
        "t" to "最高级"
    )

    private val TAG_LABELS = linkedMapOf(
        "zk" to "中考",
        "gk" to "高考",
        "cet4" to "四级",
        "cet6" to "六级",
        "ky" to "考研",
        "toefl" to "托福",
        "ielts" to "雅思",
        "gre" to "GRE"
    )

    /** ECDICT 的词性写法 → 常见写法 */
    private fun normalizePos(pos: String): String = when (pos) {
        "a.", "s." -> "adj."
        "r." -> "adv."
        else -> pos
    }

    fun toWordDetail(
        word: String,
        phonetic: String?,
        translation: String?,
        definition: String?,
        tag: String?,
        exchange: String?,
        collins: Int,
        oxford: Boolean,
        note: String? = null
    ): WordDetail? {
        val parts = parseTranslation(translation.orEmpty())
        if (parts.isEmpty()) return null

        val ex = parseExchange(exchange.orEmpty())
        val exchanges = EXCHANGE_LABELS.mapNotNull { (k, label) ->
            ex[k]?.let { WordExchange(label, listOf(it)) }
        }

        val tags = buildList {
            val t = tag.orEmpty().split(' ').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            TAG_LABELS.forEach { (k, label) -> if (k in t) add(label) }
            if (collins in 1..5) add("柯林斯" + "★".repeat(collins))
            if (oxford) add("牛津3000")
        }

        return WordDetail(
            word = word,
            phoneticOther = phonetic?.trim()?.takeIf { it.isNotEmpty() },
            parts = parts,
            exchanges = exchanges,
            tags = tags,
            englishDefinitions = parseDefinition(definition.orEmpty()),
            note = note
        )
    }

    /** 解析中文释义：每行一个词性 */
    fun parseTranslation(text: String): List<WordPart> {
        val result = mutableListOf<WordPart>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val pos = POS_LINE.find(line)
            val domain = if (pos == null) DOMAIN_LINE.find(line) else null
            val (label, body) = when {
                pos != null -> normalizePos(pos.groupValues[1]) to pos.groupValues[2]
                domain != null -> domain.groupValues[1] to domain.groupValues[2]
                else -> "" to line
            }
            val means = splitMeanings(body)
            if (means.isEmpty()) return@forEach
            // 同一词性出现多行时合并
            val idx = result.indexOfFirst { it.part == label }
            if (idx >= 0) {
                result[idx] = WordPart(label, (result[idx].means + means).distinct())
            } else {
                result += WordPart(label, means)
            }
        }
        // 领域释义（[计] [医] [网络]…）放到最后
        return result.sortedBy { if (it.part.startsWith("[")) 1 else 0 }
    }

    /** 按逗号 / 分号切分释义，括号内的逗号不切：「使(马,鹰等)戴头罩」 */
    fun splitMeanings(body: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var depth = 0
        for (c in body) {
            when (c) {
                '(', '（', '[', '【' -> { depth++; sb.append(c) }
                ')', '）', ']', '】' -> { if (depth > 0) depth--; sb.append(c) }
                ',', '，', ';', '；' -> if (depth == 0) {
                    out += sb.toString(); sb.clear()
                } else sb.append(c)
                else -> sb.append(c)
            }
        }
        out += sb.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    /** 英英释义：每行 "n. xxx"，按词性分组 */
    fun parseDefinition(text: String): List<EnglishDefinition> {
        val groups = linkedMapOf<String, MutableList<String>>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
            val m = POS_LINE.find(line)
            val pos = m?.let { normalizePos(it.groupValues[1]) } ?: ""
            val def = (m?.groupValues?.get(2) ?: line).trim()
            if (def.isNotEmpty()) groups.getOrPut(pos) { mutableListOf() } += def
        }
        return groups.map { (pos, defs) -> EnglishDefinition(pos, defs, emptyList()) }
    }

    /** "s:books/d:booked/0:book/1:dp" → {s=books, d=booked, 0=book, 1=dp} */
    fun parseExchange(text: String): Map<String, String> =
        text.split('/').mapNotNull { part ->
            val i = part.indexOf(':')
            if (i <= 0) null else part.substring(0, i).trim() to part.substring(i + 1).trim()
        }.filter { it.second.isNotEmpty() }.toMap()

    /** 变形说明，如 kinds="dp" → "过去式、过去分词" */
    fun describeKinds(kinds: String): String {
        val order = EXCHANGE_LABELS.keys.toList()
        return kinds.map { it.toString() }
            .filter { it in EXCHANGE_LABELS }
            .distinct()
            .sortedBy { order.indexOf(it) }
            .joinToString("、") { EXCHANGE_LABELS.getValue(it) }
    }
}
