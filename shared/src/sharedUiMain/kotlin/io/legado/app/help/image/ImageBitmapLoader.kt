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
 * - `bg://`: 阅读器内置背景图（平台实现负责缓存/下载）
 * - `cbz://`: 从 cbz/zip 内嵌条目读取图片流 (`cbz://{entry}` 需 [book] 非 null;
 *   native 端另支持 `cbz://{archivePath}#{entry}` 自含形式)
 * - 不支持的 scheme / 解码失败: 返回 null
 *
 * 网络请求与磁盘 IO 在 [kotlinx.coroutines.Dispatchers.IO] 执行 (actual 实现内部 withContext)。
 * 调用方负责内存缓存与错误日志 (保留各端差异化行为)。
 *
 * 平台实现:
 * - jvmMain (desktop): ImageIO + OkHttp + AnalyzeUrlCore + CbzFile (对照 app 端 ImageLoader.loadManga)
 * - androidMain: BitmapFactory + OkHttp (与 JVM 路径一致，含 bg:// 内置背景缓存)
 * - iosMain: 自下载链路与 jvm/android/ohos 同构 (Ktor/KmpHttpClient 或 AnalyzeUrlCore + Skia 解码,
 *   cbz:// 经 ArchiveProviders 直解); 书架封面等常规组件另走 Coil3 共享管线 (双链路, 见下)
 * - ohosMain: CPF 融合渲染变体提供的编码图像解码 + KmpHttpClient/AnalyzeUrlCore;
 *   cbz:// 同走 ArchiveProviders
 *
 * # 双链路设计 (2026-08 拍板, 四端统一)
 *
 * 正文图/图片预览/字节消费方走本加载器自下载链路, 解密后字节进 [ImageBytesCache]
 * (key 含 isCover, 正文图缓存与其他图片隔离, 不受封面换源/重试影响);
 * 书架封面等常规组件走 Coil3 共享管线 (fetcher 层下载+解密+磁盘缓存), 两条链路互不共享缓存。
 *
 * 网络图响应字节解密: 下载后按 [isCover] 走共享 [io.legado.app.utils.ImageUtils.decode]
 * (true=书源 coverDecodeJs 封面解密, 对齐 app 端 Glide OkHttpStreamFetcher 的封面/预览链路;
 * false=正文 imageDecode, 对齐 app 端 BookHelp.saveImage)。
 * 解密规则为空原样返回; 解密失败返回 null (调用方走失败占位, 对齐原版 "封面二次解密失败")。
 *
 * 不放 commonMain: [ImageBitmap] 需 Compose UI 依赖, 仅 sharedUiMain 有 (ohos/linuxArm64 约束)。
 */
expect class ImageBitmapLoader() {

    /**
     * 加载图片为 [ImageBitmap]。
     *
     * @param url 图片地址 (bg:// / file:// / 绝对路径 / http(s):// / cbz://)
     * @param book 当前书籍 (cbz:// 必填, http(s):// 用于判断 isLocal), 可为 null
     * @param bookSource 书源 (http(s):// 用于防盗链 header/cookie/charset/JS), 可为 null
     * @param isCover 响应字节解密规则选择: true=coverDecodeJs (原版 Glide 封面/预览链路),
     *   false=imageDecode (原版 BookHelp.saveImage 正文链路), 默认 false
     * @return 已解码 [ImageBitmap], 失败或不支持的 scheme 返回 null
     */
    suspend fun loadBitmap(
        url: String,
        book: Book?,
        bookSource: BookSource?,
        isCover: Boolean = false,
    ): ImageBitmap?

    /**
     * 取图片原始字节 (不解码), 供动图逐帧解码等需要裸字节的场景 (见 [decodeAnimatedFrames])。
     *
     * scheme 支持范围与 [loadBitmap] 一致; 网络图同样带书源防盗链 header/cookie/charset/JS,
     * 并按 [isCover] 执行响应字节解密 (规则为空原样返回)。
     * 与 [loadBitmap] 相互独立: 调用方若两者都要 (静态兜底 + 动图), 会各取一次字节
     * (desktop/鸿蒙 走 HTTP 缓存, iOS 走 Coil3 磁盘缓存, 实际不会双倍下载)。
     *
     * @param isCover 同 [loadBitmap]
     * @return 原始字节; 不支持的 scheme / 取字节失败返回 null
     */
    suspend fun loadBytes(
        url: String,
        book: Book?,
        bookSource: BookSource?,
        isCover: Boolean = false,
    ): ByteArray?
}
