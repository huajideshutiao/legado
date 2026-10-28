package io.legado.app.utils

import java.io.InputStream
import java.util.Scanner

/**
 * 单遍读取 JSON 候选全文: 首个非空白字符不是 `{`/`[` 时只读首段即放弃 (返回 null);
 * 命中候选则继续读到流末尾, 全文非 JSON 形态 (末字符不成对) 同样返回 null。
 *
 * 探测与全文共用一个 UTF-8 Reader: 跨读取边界的多字节字符由同一解码器拼接,
 * 不会在分段处被替换成乱码; 探测后重开再读会让 JSON 内容过两遍, 不可寻址源两次打开
 * 还可能取到不同内容。
 */
fun InputStream?.readJsonCandidateText(): String? {
    this ?: return null
    this.use {
        val reader = it.reader(Charsets.UTF_8)
        val head = CharArray(128)
        var count = 0
        while (count < head.size) {
            val n = reader.read(head, count, head.size - count)
            if (n < 0) break
            count += n
        }
        val headText = String(head, 0, count)
        val first = headText.firstOrNull { !it.isWhitespace() }
        if (first != null && first != '{' && first != '[') {
            return null
        }
        // 首段未读满 128 字符 = 流已到头, 全文即首段
        val text = if (count < head.size) headText else headText + reader.readText()
        return if (text.isJson()) text else null
    }
}

fun InputStream?.contains(str: String): Boolean {
    this ?: return false
    this.use {
        val scanner = Scanner(it)
        return scanner.findWithinHorizon(str, 0) != null
    }
}
