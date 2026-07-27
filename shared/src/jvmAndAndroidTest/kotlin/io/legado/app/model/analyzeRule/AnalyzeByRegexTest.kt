package io.legado.app.model.analyzeRule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.regex.Pattern

/**
 * Pattern→kotlin.text.Regex 迁移金样：以旧 java.util.regex 实现为 oracle 逐输出对照。
 */
class AnalyzeByRegexTest {

    // ---- 旧实现 oracle（迁移前 java.util.regex 版本原样保留） ----

    private fun oracleGetElement(res: String, regs: Array<String>, index: Int = 0): List<String>? {
        var vIndex = index
        val resM = Pattern.compile(regs[vIndex]).matcher(res)
        if (!resM.find()) return null
        return if (vIndex + 1 == regs.size) {
            val info = arrayListOf<String>()
            for (groupIndex in 0..resM.groupCount()) {
                info.add(resM.group(groupIndex)!!)
            }
            info
        } else {
            val result = StringBuilder()
            do {
                result.append(resM.group())
            } while (resM.find())
            oracleGetElement(result.toString(), regs, ++vIndex)
        }
    }

    private fun oracleGetElements(res: String, regs: Array<String>, index: Int = 0): List<List<String>> {
        var vIndex = index
        val resM = Pattern.compile(regs[vIndex]).matcher(res)
        if (!resM.find()) return arrayListOf()
        if (vIndex + 1 == regs.size) {
            val books = ArrayList<List<String>>()
            do {
                val info = arrayListOf<String>()
                for (groupIndex in 0..resM.groupCount()) {
                    info.add(resM.group(groupIndex) ?: "")
                }
                books.add(info)
            } while (resM.find())
            return books
        } else {
            val result = StringBuilder()
            do {
                result.append(resM.group())
            } while (resM.find())
            return oracleGetElements(result.toString(), regs, ++vIndex)
        }
    }

    // ---- 金样 ----

    private val bookListHtml = """
        <li><a href="/book/1">斗破苍穹</a><span>天蚕土豆</span></li>
        <li><a href="/book/2">凡人修仙传</a><span>忘语</span></li>
        <li><a href="/book/3">诡秘之主</a><span>爱潜水的乌贼</span></li>
    """.trimIndent()

    @Test
    fun `getElements 分组提取对照 oracle`() {
        val regs = arrayOf("<a href=\"([^\"]+)\">([^<]+)</a><span>([^<]+)</span>")
        assertEquals(
            oracleGetElements(bookListHtml, regs),
            AnalyzeByRegex.getElements(bookListHtml, regs)
        )
    }

    @Test
    fun `getElements 多级规则链对照 oracle`() {
        // 先窄化到 li 块再提取（书源两段式常用形态）
        val regs = arrayOf("<li>[\\w\\W]*?</li>", "<a href=\"([^\"]+)\">([^<]+)</a>")
        assertEquals(
            oracleGetElements(bookListHtml, regs),
            AnalyzeByRegex.getElements(bookListHtml, regs)
        )
    }

    @Test
    fun `getElements 未参与分组落空串`() {
        // 交替分支：每次匹配必有一支未参与
        val regs = arrayOf("(第[一二三]章)|(番外[0-9]+)")
        val res = "第一章 xx 番外1 yy 第二章 zz"
        val ours = AnalyzeByRegex.getElements(res, regs)
        assertEquals(oracleGetElements(res, regs), ours)
        assertEquals("", ours[0][2]) // 第一支命中时第二组空串
        assertEquals("", ours[1][1]) // 第二支命中时第一组空串
    }

    @Test
    fun `getElement 单条提取与未命中`() {
        val regs = arrayOf("作者[:：]\\s*(\\S+)")
        val res = "书名:斗破苍穹 作者：天蚕土豆 分类:玄幻"
        assertEquals(oracleGetElement(res, regs), AnalyzeByRegex.getElement(res, regs))
        assertNull(AnalyzeByRegex.getElement("无此字段", regs))
    }

    @Test
    fun `多行模式与 DOTALL 语义透传`() {
        val res = "第一行标题\n第二行内容\n第三行结尾"
        // 内联 flag (?m)(?s) 由表达式自带，两实现均须透传
        val regs = arrayOf("(?m)^第二行(.*)$")
        assertEquals(oracleGetElements(res, regs), AnalyzeByRegex.getElements(res, regs))
        val regsDotAll = arrayOf("(?s)标题(.+?)结尾")
        assertEquals(oracleGetElements(res, regsDotAll), AnalyzeByRegex.getElements(res, regsDotAll))
    }

    @Test
    fun `零宽匹配推进不死循环`() {
        // 空可选组产生零宽匹配，find() 与 next() 的推进语义须一致
        val regs = arrayOf("(x?)")
        val res = "axa"
        assertEquals(oracleGetElements(res, regs), AnalyzeByRegex.getElements(res, regs))
    }
}
