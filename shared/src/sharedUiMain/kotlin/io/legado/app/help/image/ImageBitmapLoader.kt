package io.legado.app.help.image

import androidx.compose.ui.graphics.ImageBitmap
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource

/**
 * 跨平台图片加载器 (合并 desktop loadAudioCover + loadMangaImage 重复实现)。
 *
 * 加载策略:
 * - `file://` / 绝对路径 `/...`: 直接读文件解码
 * - `http://` / `https://`: OkHttp 下载字节流后解码 (网络书带书源 header/cookie/charset/JS)
 * - `cbz://`: 从 cbz/zip 内嵌条目读取图片流 (需 [book] 非 null)
 * - 不支持的 scheme / 解码失败: 返回 null
 *
 * 网络请求与磁盘 IO 在 [kotlinx.coroutines.Dispatchers.IO] 执行 (actual 实现内部 withContext)。
 * 调用方负责内存缓存与错误日志 (保留各端差异化行为)。
 *
 * 平台实现:
 * - jvmMain (desktop): ImageIO + OkHttp + AnalyzeUrlCore + CbzFile (对照 app 端 ImageLoader.loadManga)
 * - androidMain / iosMain: 暂 stub (返回 null, 后续补 BitmapFactory / UIKit)
 * - ohosMain: 不参与 (鸿蒙 UI 用 ArkTS, 不继承 sharedUiMain)
 *
 * 不放 commonMain: [ImageBitmap] 需 Compose UI 依赖, 仅 sharedUiMain 有 (ohos/linuxArm64 约束)。
 */
expect class ImageBitmapLoader {

    /**
     * 加载图片为 [ImageBitmap]。
     *
     * @param url 图片 URL (file:// / 绝对路径 / http(s):// / cbz://)
     * @param book 当前书籍 (cbz:// 必填, http(s):// 用于判断 isLocal), 可为 null
     * @param bookSource 书源 (http(s):// 用于防盗链 header/cookie/charset/JS), 可为 null
     * @return 已解码 [ImageBitmap], 失败或不支持的 scheme 返回 null
     */
    suspend fun loadBitmap(url: String, book: Book?, bookSource: BookSource?): ImageBitmap?
}
