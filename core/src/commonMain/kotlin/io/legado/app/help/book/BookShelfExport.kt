package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.utils.exportJsonText
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 书架导出字段映射 (13 个字段清单与 app 端 `BookshelfManageActivity.exportBookshelf` 一致)。
 *
 * 仅做纯字段映射, 不做 JSON 序列化: 序列化形制统一走 [bookshelfJson]。
 *
 * 注意: `buildMap` 为 LinkedHashMap, 键序即插入序, 消费方按 map 序写 JSON 可得到与
 * 原实现完全一致的字段顺序; null 字段跳过 (对齐原 GSON `serializeNulls=false` 语义)。
 */
fun Book.toShelfJsonMap(): Map<String, Any?> = buildMap {
    put("bookUrl", bookUrl)
    put("tocUrl", tocUrl)
    put("origin", origin)
    put("originName", originName)
    put("name", name)
    put("author", author)
    kind?.let { put("kind", it) }
    coverUrl?.let { put("coverUrl", it) }
    customCoverUrl?.let { put("customCoverUrl", it) }
    intro?.let { put("intro", it) }
    customIntro?.let { put("customIntro", it) }
    put("type", type)
    wordCount?.let { put("wordCount", it) }
}

/**
 * 书架导出 JSON 文本 (四端共用): 字段与顺序取自 [toShelfJsonMap], 跳过 null,
 * 按原版 `JsonWriter.setIndent("  ")` 的 2 空格缩进输出。
 */
fun bookshelfJson(books: List<Book>): String = exportJsonText(
    buildJsonArray {
        books.forEach { book ->
            add(
                buildJsonObject {
                    book.toShelfJsonMap().forEach { (key, value) ->
                        when (value) {
                            is String -> put(key, value)
                            is Number -> put(key, value)
                            is Boolean -> put(key, value)
                            is JsonElement -> put(key, value)
                            else -> Unit
                        }
                    }
                }
            )
        }
    }
)
