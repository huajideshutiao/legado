package io.legado.app.utils

import java.io.InputStream
import java.util.Scanner

/**
 * 单遍读取 JSON 候选全文: 首个非空白字符不是 `{`/`[` 时只读首段即放弃 (返回 null, 仅耗
 * 128 字节); 命中候选则读至流末尾, 全文非 JSON 形态 (末字符不成对) 同样返回 null。
 *
 * 探测与全文共用同一次流: 探测后重开再读会让 JSON 内容过两遍, 不可寻址源两次打开
 * 还可能取到不同内容。
 */
fun InputStream?.readJsonCandidateText(): String? {
    this ?: return null
    this.use {
        val head = it.readUpTo(128)
        val headText = String(head)
        val first = headText.firstOrNull { !it.isWhitespace() }
        if (first != null && first != '{' && first != '[') {
            return null
        }
        // 首段未读满 128 字节 = 流已到头, 全文即首段
        val text = if (head.size < 128) headText else headText + String(it.readBytes())
        return if (text.isJson()) text else null
    }
}

/** 读满 [size] 字节或遇流末尾, 返回实际读到的字节。 */
private fun InputStream.readUpTo(size: Int): ByteArray {
    val buffer = ByteArray(size)
    var offset = 0
    while (offset < size) {
        val n = read(buffer, offset, size - offset)
        if (n < 0) break
        offset += n
    }
    return if (offset == size) buffer else buffer.copyOf(offset)
}

fun InputStream?.contains(str: String): Boolean {
    this ?: return false
    this.use {
        val scanner = Scanner(it)
        return scanner.findWithinHorizon(str, 0) != null
    }
}
