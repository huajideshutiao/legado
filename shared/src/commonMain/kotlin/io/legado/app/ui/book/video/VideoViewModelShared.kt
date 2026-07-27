package io.legado.app.ui.book.video

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.VideoResolution
import io.legado.app.data.entities.VideoSource
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJsonObject

/**
 * 视频源内容解析算法 (KMP 共享)。
 *
 * 原 app 端 `VideoViewModel.parseVideoContent` 中的纯解析逻辑下沉:
 * - [parseVideoSource]: JSON / `::` 分隔格式 → [VideoSource]
 * - [extractVideoUrlAndReferer]: `#BASE:` 前缀格式 → (videoUrl, Referer)
 *
 * 零 Android 依赖 (不含 LiveData / AnalyzeUrl / postValue),
 * app 端 VideoViewModel 调用本文件函数后再做平台专属的 LiveData 推送与 AnalyzeUrl 构造。
 * 实现逻辑与 app 端原方法完全一致, 仅做位置迁移, 未改变任何解析步骤。
 */

/**
 * 解析视频源内容为 [VideoSource]。
 *
 * 支持两种格式:
 * 1. JSON 对象: `GSON.fromJsonObject<VideoSource>` 反序列化, 要求至少一个 resolution 的 url 非空;
 *    解析异常时记录日志 (AppLog) 并返回 null (与原实现一致)。
 * 2. `::` 分隔的多行文本: 每行 `name::url` 拆分为 [VideoResolution], 过滤空 url;
 *    至少一个有效 resolution 才返回 [VideoSource], 否则 null。
 *
 * 非 JSON 且不含 `::`+换行 的内容返回 null (交由调用方走 [extractVideoUrlAndReferer] 分支)。
 *
 * @param content 视频源原始内容
 * @return 解析出的 [VideoSource], 不匹配或解析失败返回 null
 */
fun parseVideoSource(content: String): VideoSource? {
    val source = if (content.isJsonObject()) {
        try {
            GSON.fromJsonObject<VideoSource>(content).getOrNull()
                ?.takeIf { it.resolutions.any { r -> r.url.isNotEmpty() } }
        } catch (e: Exception) {
            AppLog.put("解析视频源JSON出错\n$e", e)
            null
        }
    } else if (content.contains("::") && content.contains("\n")) {
        val resolutions = content.lines().filter { it.contains("::") }.mapNotNull { line ->
            val parts = line.split("::", limit = 2)
            if (parts.size == 2) {
                VideoResolution(
                    name = parts[0].trim(), url = parts[1].trim()
                )
            } else null
        }.filter { it.url.isNotEmpty() }

        if (resolutions.isNotEmpty()) {
            VideoSource(resolutions = resolutions)
        } else null
    } else null
    return source
}

/**
 * 从内容中提取视频 URL 与 Referer (fakeUrl)。
 *
 * 处理 `#BASE:` 前缀格式: 首行 `#BASE:xxx` 的 xxx 作为 Referer, 其余部分作为视频 URL。
 * 非 `#BASE:` 格式时, 视频 URL 为完整 content, Referer 默认 `https://example.com/memory.m3u8`。
 *
 * 对应原 `parseVideoContent` else 分支中 `AnalyzeUrl("").apply { ... }` 内的纯字符串计算部分,
 * LiveData 推送与 AnalyzeUrl 构造仍留在 app 端。
 *
 * @param content 视频源原始内容 (非 http 开头分支)
 * @return (videoUrl, fakeUrl) 二元组
 */
fun extractVideoUrlAndReferer(content: String): Pair<String, String> {
    var videoUrl = content
    val fakeUrl = if (content.startsWith("#BASE:")) {
        val index = content.indexOf("\n") + 1
        videoUrl = content.substring(index)
        content.substring(6, index - 1)
    } else "https://example.com/memory.m3u8"
    return videoUrl to fakeUrl
}
