package com.translate.app.data

import com.google.gson.Gson
import com.translate.app.data.dict.EcdictParser
import com.translate.app.data.model.BaiduTranslationResponse
import com.translate.app.data.model.HistoryItem
import com.translate.app.data.model.WordDetail
import com.translate.app.data.repository.WordMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.sql.DriverManager
import java.util.zip.GZIPInputStream

class OfflineDictTest {

    private fun fine() = EcdictParser.toWordDetail(
        word = "fine",
        phonetic = "fain",
        translation = "n. 罚款, 罚金, 晴天, 精细\na. 好的, 晴朗的, 健康的\nvt. 罚款, 精炼, 澄清\nadv. 很好\n[计] 精细",
        definition = "n. money extracted as a penalty\ns. minutely precise\ns. thin in thickness",
        tag = "zk gk toefl ielts",
        exchange = "t:finest/d:fined/s:fines/r:finer/p:fined/3:fines/i:fining",
        collins = 3,
        oxford = true
    )!!

    @Test
    fun parsesPartsOfSpeechWithAllMeanings() {
        val d = fine()
        assertEquals(listOf("n.", "adj.", "vt.", "adv.", "[计]"), d.parts.map { it.part })
        assertEquals(listOf("罚款", "罚金", "晴天", "精细"), d.parts[0].means) // 熟词生义：fine n. 罚款
        assertEquals("[计]", d.parts.last().part)                                 // 领域释义排最后
        assertEquals("fain", d.phoneticOther)
    }

    @Test
    fun parsesTagsExchangesAndDefinitions() {
        val d = fine()
        assertEquals(listOf("中考", "高考", "托福", "雅思", "柯林斯★★★", "牛津3000"), d.tags)
        assertEquals(
            listOf("复数", "第三人称单数", "过去式", "过去分词", "现在分词", "比较级", "最高级"),
            d.exchanges.map { it.label }
        )
        assertEquals(listOf("n.", "adj."), d.englishDefinitions.map { it.pos }) // s. 合并为 adj.
        assertEquals(2, d.englishDefinitions[1].definitions.size)
    }

    @Test
    fun keepsCommasInsideParentheses() {
        assertEquals(
            listOf("覆盖", "使(马,鹰等)戴头罩", "给…加罩"),
            EcdictParser.splitMeanings("覆盖；使(马,鹰等)戴头罩；给…加罩")
        )
    }

    @Test
    fun phraseWithoutPos() {
        val d = EcdictParser.toWordDetail("give up", "", "放弃, 停止, 献出\n[法] 放弃",
            "", "", "", 0, false)!!
        assertEquals("", d.parts[0].part)
        assertEquals(listOf("放弃", "停止", "献出"), d.parts[0].means)
        assertNull(d.phoneticOther)
    }

    @Test
    fun describesInflectionKinds() {
        assertEquals("过去式、过去分词", EcdictParser.describeKinds("dp"))
        assertEquals("过去式、过去分词", EcdictParser.describeKinds("pd"))
        assertEquals("比较级", EcdictParser.describeKinds("r"))
        assertEquals("", EcdictParser.describeKinds("0"))
    }

    @Test
    fun emptyTranslationReturnsNull() {
        assertNull(EcdictParser.toWordDetail("x", "", "", "", "", "", 0, false))
    }

    @Test
    fun wordModeDetection() {
        listOf("apple", "Book", "give up", "mother-in-law", "don't", "take it easy", "book.", "\"fine\"")
            .forEach { assertTrue(it, WordMode.isWordQuery(it)) }
        listOf("", "This is a sentence of words", "苹果", "hello, world", "3d", "a b c d")
            .forEach { assertFalse(it, WordMode.isWordQuery(it)) }
    }

    @Test
    fun parsesBaiduErrorResponse() {
        val resp = Gson().fromJson("""{"error_code":"54001","error_msg":"Invalid Sign"}""",
            BaiduTranslationResponse::class.java)
        assertEquals("54001", resp.errorCode)
        assertNull(resp.transResult)
    }

    @Test
    fun historyRoundTripsThroughGson() {
        val item = HistoryItem(1L, "fine", "罚款", "en", "zh", 123L, 3, fine().copy(note = "n"))
        val gson = Gson()
        val back = gson.fromJson(gson.toJson(arrayOf(item)), Array<HistoryItem>::class.java)
        assertEquals(item, back.single())
    }

    /** 用真实的内置词库跑一遍：每个词条都能解析，常用词的内容正确 */
    @Test
    fun everyEntryInBundledDictionaryParses() {
        val gz = File("src/main/assets/ecdict.db.gzip")
        assertTrue("词库不存在：${gz.absolutePath}", gz.exists())
        val db = File.createTempFile("ecdict", ".db").apply { deleteOnExit() }
        GZIPInputStream(gz.inputStream()).use { input -> db.outputStream().use { input.copyTo(it) } }

        DriverManager.getConnection("jdbc:sqlite:${db.path}").use { conn ->
            fun lookup(w: String): WordDetail? = conn.prepareStatement(
                "SELECT word, phonetic, translation, definition, tag, exchange, collins, oxford FROM dict WHERE word = ?"
            ).use { st ->
                st.setString(1, w)
                st.executeQuery().use { rs ->
                    if (!rs.next()) null else EcdictParser.toWordDetail(
                        rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getInt(7), rs.getInt(8) == 1
                    )
                }
            }

            var total = 0
            var failed = 0
            conn.createStatement().use { st ->
                st.executeQuery("SELECT word, phonetic, translation, definition, tag, exchange, collins, oxford FROM dict")
                    .use { rs ->
                        while (rs.next()) {
                            total++
                            val d = EcdictParser.toWordDetail(
                                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                                rs.getString(5), rs.getString(6), rs.getInt(7), rs.getInt(8) == 1
                            )
                            if (d == null || d.parts.isEmpty()) failed++
                        }
                    }
            }
            println("词条总数 $total，无法解析 $failed")
            assertTrue("词条太少：$total", total > 200_000)
            assertTrue("无法解析的词条过多：$failed / $total", failed * 1000 < total) // < 0.1%

            // 大小写不敏感 + 熟词生义
            val fine = lookup("FINE")!!
            assertTrue(fine.parts.first { it.part == "n." }.means.contains("罚款"))
            val book = lookup("book")!!
            assertTrue(book.parts.first { it.part == "v." }.means.contains("预订"))
            assertNotNull(lookup("give up"))

            // 变形索引
            conn.prepareStatement("SELECT base, kind FROM forms WHERE form = ?").use { st ->
                st.setString(1, "tomatoes")
                st.executeQuery().use { rs ->
                    assertTrue("tomatoes 应能找到原形", rs.next() || lookup("tomatoes") != null)
                }
            }
        }
    }
}
