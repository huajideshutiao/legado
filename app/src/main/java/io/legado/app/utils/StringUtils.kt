package io.legado.app.utils

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.text.DecimalFormat
import java.util.regex.Pattern
import java.util.zip.GZIPOutputStream


@Suppress("MemberVisibilityCanBePrivate")
object StringUtils {
    private val ChnMap = chnMap
    private val wordCountFormatter by lazy {
        DecimalFormat("#.#")
    }

    private val chnMap: HashMap<Char, Int>
        get() {
            val map = HashMap<Char, Int>()
            var cnStr = "零一二三四五六七八九十"
            var c = cnStr.toCharArray()
            for (i in 0..10) {
                map[c[i]] = i
            }
            cnStr = "〇壹贰叁肆伍陆柒捌玖拾"
            c = cnStr.toCharArray()
            for (i in 0..10) {
                map[c[i]] = i
            }
            map['两'] = 2
            map['百'] = 100
            map['佰'] = 100
            map['千'] = 1000
            map['仟'] = 1000
            map['万'] = 10000
            map['亿'] = 100000000
            return map
        }

    /**
     * 字符串全角转换为半角
     */
    fun fullToHalf(input: String): String {
        val c = input.toCharArray()
        for (i in c.indices) {
            if (c[i].code == 12288)
            //全角空格
            {
                c[i] = 32.toChar()
                continue
            }

            if (c[i].code in 65281..65374) c[i] = (c[i].code - 65248).toChar()
        }
        return String(c)
    }

    /**
     * 中文大写数字转数字
     */
    fun chineseNumToInt(chNum: String): Int {
        var result = 0
        var tmp = 0
        var billion = 0
        val cn = chNum.toCharArray()

        // "一零二五" 形式
        if (cn.size > 1 && chNum.matches("^[〇零一二三四五六七八九壹贰叁肆伍陆柒捌玖]$".toRegex())) {
            for (i in cn.indices) {
                cn[i] = (48 + ChnMap[cn[i]]!!).toChar()
            }
            return Integer.parseInt(String(cn))
        }

        // "一千零二十五", "一千二" 形式
        return kotlin.runCatching {
            for (i in cn.indices) {
                val tmpNum = ChnMap[cn[i]]!!
                when {
                    tmpNum == 100000000 -> {
                        result += tmp
                        result *= tmpNum
                        billion = billion * 100000000 + result
                        result = 0
                        tmp = 0
                    }

                    tmpNum == 10000 -> {
                        result += tmp
                        result *= tmpNum
                        tmp = 0
                    }

                    tmpNum >= 10 -> {
                        if (tmp == 0) tmp = 1
                        result += tmpNum * tmp
                        tmp = 0
                    }

                    else -> {
                        tmp =
                            if (i >= 2 && i == cn.size - 1 && ChnMap[cn[i - 1]]!! > 10) tmpNum * ChnMap[cn[i - 1]]!! / 10
                            else tmp * 10 + tmpNum
                    }
                }
            }
            result += tmp + billion
            result
        }.getOrDefault(-1)
    }

    /**
     * 字符串转数字
     */
    fun stringToInt(str: String?): Int {
        val num = str?.let { fullToHalf(it).replace("\\s+".toRegex(), "") } ?: return -1
        return kotlin.runCatching {
            Integer.parseInt(num)
        }.getOrElse {
            chineseNumToInt(num)
        }
    }

    /**
     * 是否数字
     */
    fun isNumeric(str: String): Boolean {
        val pattern = Pattern.compile("-?[0-9]+")
        val isNum = pattern.matcher(str)
        return isNum.matches()
    }

    fun wordCountFormat(words: Int): String {
        var wordsS = ""
        if (words > 0) {
            if (words > 10000) {
                val df = wordCountFormatter
                wordsS = df.format(words * 1.0f / 10000f.toDouble()) + "万字"
            } else {
                wordsS = words.toString() + "字"
            }
        }
        return wordsS
    }

    fun wordCountFormat(wc: String?): String {
        if (wc.isNullOrEmpty()) return ""

        val prefix = wc.substringBefore(",")
        val suffix = wc.substringAfter(",", "")
        val suffixPart = if (suffix.isNotEmpty()) ",$suffix" else ""

        val words = prefix.toIntOrNull()

        val formattedPrefix = when {
            words == null -> prefix // 修复：原代码为 wc，此处改为 prefix 以避免拼接重复
            words > 10000 -> "${wordCountFormatter.format(words / 10000.0)}万字"
            words > 0 -> "${words}字"
            else -> "" // 保持原逻辑：如果 words <= 0，返回空字符串
        }

        return formattedPrefix + suffixPart
    }

    /**
     * 压缩字符串
     */
    fun compress(str: String): Result<String> {
        return kotlin.runCatching {
            if (str.isEmpty()) {
                return@runCatching str
            }
            val out = ByteArrayOutputStream()
            var gzip: GZIPOutputStream? = null
            return@runCatching try {
                gzip = GZIPOutputStream(out)
                gzip.write(str.toByteArray())
                Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            } finally {
                gzip?.runCatching {
                    close()
                }
                out.runCatching {
                    close()
                }
            }
        }
    }

}
