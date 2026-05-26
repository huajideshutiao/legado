package io.legado.app.model

import org.mozilla.javascript.NativeArray

/**
 * 解析书源 lrc 规则返回的歌词数据。
 *
 * 输入是一个 NativeArray<String>,每个元素本身是一段多行 LRC 文本:
 * - 第 0 个元素:每个时间戳行作为新条目加入 (time -> text)
 * - 后续元素:按时间戳匹配并把文本以换行附加到已有条目上(用于多语种叠加,常见于网易云双语歌词)
 *
 * 时间戳支持 [mm:ss] 与 [mm:ss.fff]。无时间戳的行会作为 (-1, line) 加入(显示在最前)。
 *
 * 兼容修复:把字符串中的 "00-1" 替换为 "000",修复部分网易云歌词把 ms 字段写成负数导致的解析错误。
 */
object LrcParser {

    fun parse(content: NativeArray): List<Pair<Int, String>> {
        val tmp = mutableListOf<Pair<Int, String>>()
        for (i in content.indices) {
            (content[i] as String).replace("00-1", "000")
                .lineSequence().forEach { rawLine ->
                    val line = rawLine.trim()
                    if (line.length < 3) return@forEach
                    val close = line.indexOf("]")
                    if (close == -1) {
                        tmp.add(Pair(-1, line))
                        return@forEach
                    }
                    if (!line[1].isDigit()) return@forEach
                    val textPart = line.substring(close + 1)
                    val numbers = Regex("\\d+").findAll(line.substring(1, close)).iterator()
                    if (!numbers.hasNext()) return@forEach
                    val min = numbers.next().value.toInt()
                    if (!numbers.hasNext()) return@forEach
                    val sec = numbers.next().value.toInt()
                    val ms = if (numbers.hasNext()) {
                        numbers.next().value.padEnd(3, '0').toInt()
                    } else 0
                    val time = min * 60_000 + sec * 1000 + ms
                    if (i == 0) {
                        tmp.add(Pair(time, textPart))
                    } else {
                        if (textPart.isBlank()) return@forEach
                        // 仅当首轮已有该时间戳时才合并文本,找不到就跳过
                        val index = tmp.binarySearch { it.first.compareTo(time) }
                        if (index < 0) return@forEach
                        tmp[index] = Pair(time, "${tmp[index].second}\n$textPart")
                    }
                }
            // 部分歌词源时间戳乱序(如网易云),首轮加入后按时间排序
            if (i == 0) tmp.sortBy { it.first }
        }
        return tmp
    }
}
