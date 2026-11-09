package com.translate.app.data

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.google.gson.reflect.TypeToken
import com.translate.app.data.dict.DictParser
import com.translate.app.data.model.BaiduTranslationResponse
import com.translate.app.data.model.HistoryItem
import com.translate.app.data.repository.WordMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DictParserTest {

    private val enDict = """
        {"lang":"1","word_result":{
          "simple_means":{
            "word_name":"book",
            "word_means":["书","本子","预订"],
            "exchange":{"word_pl":["books"],"word_third":["books"],"word_past":["booked"],
                        "word_done":["booked"],"word_ing":["booking"],"word_er":"","word_est":""},
            "tags":{"core":["高考","CET4"],"other":[""]},
            "symbols":[{"ph_en":"bʊk","ph_am":"bʊk","ph_other":"",
              "parts":[{"part":"n.","means":["书;","本子","卷"]},
                       {"part":"v.","means":["预订","登记","（警察）记录在案"]}]}]
          },
          "edict":{"item":[{"pos":"verb","tr_group":[
            {"tr":["arrange for and reserve in advance"],"example":["I booked a table"],"similar_word":["reserve"]}
          ]}],"word":"book"}
        }}
    """.trimIndent()

    @Test
    fun parsesObjectDict() {
        val d = DictParser.parse(JsonParser.parseString(enDict), "book")
        assertNotNull(d); d!!
        assertEquals("book", d.word)
        assertEquals("bʊk", d.phoneticUk)
        assertEquals("bʊk", d.phoneticUs)
        assertEquals(2, d.parts.size)
        assertEquals("n.", d.parts[0].part)
        assertEquals(listOf("书", "本子", "卷"), d.parts[0].means)   // 末尾分号被去掉
        assertEquals("v.", d.parts[1].part)
        assertTrue(d.parts[1].means.contains("预订"))               // 熟词生义
        assertEquals(listOf("高考", "CET4"), d.tags)
        assertEquals(listOf("复数", "第三人称单数", "过去式", "过去分词", "现在分词"),
            d.exchanges.map { it.label })                             // 空字符串的比较级/最高级被跳过
        assertEquals(1, d.englishDefinitions.size)
        assertEquals("verb", d.englishDefinitions[0].pos)
        assertEquals(listOf("I booked a table"), d.englishDefinitions[0].examples)
        assertEquals("n. 书；本子；卷  v. 预订；登记；（警察）记录在案", d.summary())
    }

    @Test
    fun parsesStringEncodedDict() {
        // dict 以 JSON 字符串形式返回（需要二次解析）
        val d = DictParser.parse(JsonPrimitive(enDict), "book")
        assertNotNull(d)
        assertEquals(2, d!!.parts.size)
    }

    @Test
    fun parsesDictInsideFullResponse() {
        val body = """{"from":"en","to":"zh","trans_result":[{"src":"book","dst":"书",
            "dict":${Gson().toJson(enDict)},"src_tts":"https://x/a.mp3","dst_tts":"https://x/b.mp3"}]}"""
        val resp = Gson().fromJson(body, BaiduTranslationResponse::class.java)
        val first = resp.transResult!!.first()
        assertEquals("https://x/a.mp3", first.srcTts)
        assertNotNull(DictParser.parse(first.dict, "book"))
    }

    @Test
    fun parsesErrorResponse() {
        val resp = Gson().fromJson("""{"error_code":"54001","error_msg":"Invalid Sign"}""",
            BaiduTranslationResponse::class.java)
        assertEquals("54001", resp.errorCode)
        assertNull(resp.transResult)
        assertNull(resp.from)
    }

    @Test
    fun parsesChineseStyleMeansObjects() {
        val zh = """{"lang":"0","word_result":{"simple_means":{"word_name":"苹果",
            "symbols":[{"word_symbol":"píng guǒ","parts":[{"part_name":"名","means":[
              {"text":"apple","part":"n.","word_mean":"苹果","means":["apple"]},
              {"text":"apple tree"}]}]}]}}}"""
        val d = DictParser.parse(JsonParser.parseString(zh), "苹果")!!
        assertEquals("píng guǒ", d.phoneticOther)
        assertEquals("名", d.parts[0].part)
        assertEquals(listOf("apple", "apple tree"), d.parts[0].means)
    }

    @Test
    fun fallsBackToWordMeans() {
        val j = """{"word_result":{"simple_means":{"word_name":"hi","word_means":["嗨","你好"]}}}"""
        val d = DictParser.parse(JsonParser.parseString(j), "hi")!!
        assertEquals(listOf("嗨", "你好"), d.parts.single().means)
    }

    @Test
    fun returnsNullForMissingOrGarbage() {
        assertNull(DictParser.parse(null, "x"))
        assertNull(DictParser.parse(JsonPrimitive(""), "x"))
        assertNull(DictParser.parse(JsonPrimitive("not json"), "x"))
        assertNull(DictParser.parse(JsonParser.parseString("""{"lang":"1","word_result":{}}"""), "x"))
        assertNull(DictParser.parse(JsonParser.parseString("""{"word_result":{"simple_means":{"symbols":"oops"}}}"""), "x"))
    }

    @Test
    fun wordModeDetection() {
        listOf("apple", "Book", "give up", "mother-in-law", "don't", "take it easy").forEach {
            assertTrue(it, WordMode.isWordQuery(it))
        }
        listOf("", "This is a sentence of words", "苹果", "hello, world", "3d", "a b c d").forEach {
            assertFalse(it, WordMode.isWordQuery(it))
        }
    }

    @Test
    fun historyRoundTripsThroughGson() {
        val detail = DictParser.parse(JsonParser.parseString(enDict), "book")
        val item = HistoryItem(1L, "book", "书", "en", "zh", 123L, 3, detail)
        val gson = Gson()
        val type = object : TypeToken<List<HistoryItem>>() {}.type
        val back: List<HistoryItem> = gson.fromJson(gson.toJson(listOf(item)), type)
        assertEquals(item, back.single())
    }
}
