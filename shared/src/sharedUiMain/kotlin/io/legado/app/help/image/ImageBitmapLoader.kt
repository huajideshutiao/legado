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
 *
 * # 解码位图进程级 LRU (2026 图片加载深度优化 I1)
 *
 * [loadBitmap] 解码结果进 [DecodedBitmapCache] (进程级 LRU, 容量沿 AppConfig.bitmapCacheSize,
 * key 含 url+书源+isCover+采样尺寸), 同 URL 二次打开零重复解码; 验证码等同 URL 每次返回
 * 新图的场景传 `useBitmapCache=false` 绕过; 统一清缓存入口见 [DecodedBitmapCache] KDoc。
 *
 * # 目标尺寸采样 (I7)
 *
 * [loadBitmap] 传 [widthPx]/[heightPx] (>0) 时按目标尺寸采样解码 (对齐 [BookImageLoader]
 * loadImageOrNull 的尺寸契约; 平台实现: jvm=ImageIO 解码前采样 / android=inSampleSize /
 * ios=Skia 解码后缩放 / ohos=保持全尺寸); 不传保持各端原语义 (jvm/iOS/ohos 全尺寸,
 * android 长边 ≤2048 防 OOM)。采样只发生在 decode 阶段, 字节缓存不变 (原图字节可再解)。
 *
 * # SVG 兜底 (2026 对齐原版 SvgUtils)
 *
 * 栅格解码失败后进 [decodeSvgFallback] (对照原版 `SvgUtils.createBitmap` 兜底语义:
 * `decodeBitmap ?: SvgUtils.createBitmap`): jvm=jsvg (SvgRasterizer) / android=androidsvg /
 * ios=Skia SVGDOM / ohos=Skia SVGDOM。SVG 矢量任意缩放, 按目标长边等比渲染 (只缩不放),
 * 渲染结果与栅格图同规则进 [DecodedBitmapCache] (key 语义不变, 同字节同尺寸渲染确定)。
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
     * @param widthPx 目标显示宽度 (px), >0 时按目标尺寸采样解码; 默认 0 不采样
     *   (各端保持原语义: jvm/iOS/ohos 全尺寸, android 长边 ≤2048 防 OOM)
     * @param heightPx 目标显示高度 (px), >0 时按目标尺寸采样解码; 默认 0 不采样
     * @param useBitmapCache 是否进 [DecodedBitmapCache] 进程级位图 LRU, 默认 true;
     *   验证码等同 URL 每次返回新图的场景传 false 绕过 (避免二次打开显示旧图)
     * @return 已解码 [ImageBitmap], 失败或不支持的 scheme 返回 null
     */
    suspend fun loadBitmap(
        url: String,
        book: Book?,
        bookSource: BookSource?,
        isCover: Boolean = false,
        widthPx: Int = 0,
        heightPx: Int = 0,
        useBitmapCache: Boolean = true,
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

/**
 * 带目标长边上限的位图解码 (采样只发生在 decode 阶段, 字节缓存不变)。
 *
 * 供 [ReaderImageCache] (阅读页内嵌图, 按页宽量级采样) 等已持有字节的消费点使用;
 * [ImageBitmapLoader.loadBitmap] 内部同样走它 (经 [DecodedBitmapCache] LRU)。
 *
 * 平台实现:
 * - jvm (desktop): ImageIO `ImageReader.setSourceSubsampling` 解码前采样 (峰值内存/解码耗时双降)
 * - android: BitmapFactory `inJustDecodeBounds` + `inSampleSize` 解码前采样
 * - ios: Skia 全量解码后 Canvas 缩放 (省常驻内存与绘制带宽, 解码峰值内存不变)
 * - ohos: 保持全尺寸解码 (CPF 融合渲染管线暂无解码前采样参数, 解码后缩放路径未验证)
 *
 * 仅处理栅格格式; SVG 由调用方在本函数返回 null 后走 [decodeSvgFallback]
 * (本函数保持纯栅格语义, SVG 兜底不混入, 各调用点显式串联)。
 *
 * @param bytes 编码图片字节
 * @param maxDim 目标长边上限 (px); <=0 全尺寸解码
 * @return 解码后位图; 无法识别/解码失败返回 null (调用方回落 SVG 兜底/全尺寸解码或占位)
 */
expect fun decodeBytesSampled(bytes: ByteArray, maxDim: Int): ImageBitmap?

/**
 * SVG 兜底解码 (对照原版 `SvgUtils.createBitmap` 兜底语义: 栅格解码失败后按 SVG 渲染)。
 *
 * SVG 是矢量无固定像素尺寸, 按 [maxDim] 长边上限等比渲染 (只缩不放, 对齐原版
 * SvgUtils.createBitmap 的 ratio 语义; 宽高比保持, 排版取尺寸/绘制共用同一比例)。
 * 渲染结果确定性 (同字节同目标尺寸同结果), 可安全进 [DecodedBitmapCache] / [ReaderImageCache]
 * 等位图缓存, 缓存 key 语义与栅格图一致无需区分。
 *
 * 平台实现:
 * - jvm (desktop): jsvg (`SvgRasterizer.toPng` 渲染 PNG 后 ImageIO 解码, 复用现有实现)
 * - android: androidsvg (`SVG.getFromInputStream` + `renderToCanvas`, 对齐
 *   `ImageProvider.android.kt` SvgDecode / app 端 SvgUtils.renderInto 的渲染参数)
 * - ios: Skia `org.jetbrains.skia.svg.SVGDOM` (skiko commonMain API, 字节→Surface 渲染)
 * - ohos: 同 ios (CPF fork 的 skiko klib 含 svg 包, 兼容门面 API)
 *
 * @param bytes SVG 编码字节
 * @param maxDim 目标长边上限 (px); <=0 按 2048 默认上限 (对齐 android 防 OOM 语义与
 *   jsvg SvgRasterizer 的 MAX_EDGE)
 * @return 渲染后位图; 非 SVG / 无固有尺寸 (无 width/height/viewBox) / 渲染失败返回 null
 *   (调用方回落失败占位)
 */
expect fun decodeSvgFallback(bytes: ByteArray, maxDim: Int): ImageBitmap?
