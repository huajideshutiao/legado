package io.legado.app.help.book

import io.legado.app.data.entities.Book

/**
 * 书架导出字段映射 (iOS 与 app/Android 端共用, 13 个字段清单与 app 端
 * `BookshelfManageActivity.exportBookshelf` / `AndroidPlatformCapabilities.exportBookshelf` 一致)。
 *
 * 仅做纯字段映射, 不做 JSON 序列化: 各端保留自身序列化/格式化
 * (iOS/desktop/鸿蒙走 `GSON.toJson(books.map { it.toShelfJsonMap() })`,
 * app 端走 kotlinx prettyPrint buildJsonArray), 保证导出格式与现状完全一致。
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
