package io.legado.app.ui.bookshelf

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.legado.app.data.entities.Book
import io.legado.app.help.http.KmpRequestBuilder
import io.legado.app.help.http.OkHttpClientProviders
import kotlinx.cinterop.readBytes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation

/**
 * iOS 端 BookCover 加载组件 (对齐桌面端 [io.legado.desktop.ui.component.DesktopBookCover] API)。
 *
 * # 加载策略 (与桌面端 DesktopBookCover 对齐, 平台差异在解码层)
 *
 * - **本地路径** (`file://` 或绝对路径 `/...`): [UIImage(contentsOfFile:)] 加载 (任务要求),
 *   再 [UIImagePNGRepresentation] 转 PNG bytes, 最后用 Skia [SkiaImage.makeFromEncoded] 转 [ImageBitmap]
 *   (因 Compose Multiplatform iOS 用 Skia 渲染, 无 UIImage → ImageBitmap 直接桥接 API,
 *   故经 PNG bytes 中转; 与桌面端 `ImageIO.read(file) + toComposeImageBitmap` 功能等价,
 *   同时利用 iOS 系统级图片解码能力, 支持 HEIC/WebP 等格式)
 * - **网络路径** (`http://`/`https://`): 用项目已注册的 [OkHttpClientProviders] 取 KmpHttpClient
 *   下载字节流 (iOS 端 KmpHttpClient 实际为 Ktor CIO 包装, API 与 OkHttp 一致; 参照桌面端
 *   `downloadAndDecode` 与 shared 端 JvmBookImageStorage.saveImages 的下载逻辑),
 *   [UIImage(data:)] 验证图片有效性 (任务要求), 同样经 PNG bytes + Skia 转 [ImageBitmap]
 * - **内存缓存**: 全局 [coverCache] 缓存已解码 [ImageBitmap] (与桌面端一致, 避免详情页内
 *   多次重组时重复下载/解码; BlurCoverBg 与 InfoCover 共享同一 Book 的封面, 命中缓存零开销)
 * - **加载失败/进行中**: 走占位视觉 (与桌面端 DesktopBookCover 完全一致:
 *   BlurCoverBg 用 accent 半透明底, InfoCover 用书名首字 + accent 底, IntroImage 用文本占位)
 *
 * # 提供的 Composable (与桌面端 DesktopBookCover 一一对应)
 *
 * - [IosBlurCoverBg]: 详情页顶部模糊封面背景 (对应 DesktopBookCover.BlurCoverBg,
 *   替代 app 端 Glide + BookInfoBgTransformation)
 * - [IosInfoCover]: 详情页封面图 (对应 DesktopBookCover.InfoCover,
 *   替代 app 端 AndroidView + CoverImageView + Glide)
 * - [IosIntroImage]: 简介内整宽插图 (对应 DesktopBookCover.IntroImage,
 *   替代 app 端 Glide + CustomTarget<Bitmap>)
 *
 * # 约束 (与桌面端一致)
 *
 * - 不引入 Glide/Coil/Kamel 等新库, 仅用 UIKit + 项目已有 OkHttp (KmpHttpClient) + Skia
 * - 不修改 shared 模块 slot 契约
 * - 不擅自修改 ui 样式 (宽高边距与桌面端占位完全一致)
 *
 * # macOS 编译命令 (Windows 无法编译 iOS target)
 *
 * ```
 * ./gradlew :shared:compileKotlinIosArm64
 * ./gradlew :shared:compileKotlinIosSimulatorArm64
 * ./gradlew :shared:linkDebugFrameworkIosArm64
 * ./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
 * ```
 */

/**
 * 详情页顶部模糊封面背景 (对应桌面端 DesktopBookCover.BlurCoverBg)。
 *
 * - 加载成功: 封面图 [ContentScale.Crop] 铺满 + [blur] 模糊 + accent 半透明遮罩
 *   (与 app 端 BookInfoBgTransformation 的模糊+降饱和视觉接近)
 * - 加载中/失败: 回退到原纯色占位 `Color(0xFF165DFF).copy(alpha = 0.15f)`
 *
 * @param book 当前书籍 (可能为 null, 取 [Book.getDisplayCover] 得封面 URL)
 * @param modifier 由 shared blurCoverBgSlot 传入的尺寸约束 (保持原占位布局)
 */
@Composable
fun IosBlurCoverBg(book: Book?, modifier: Modifier = Modifier) {
    val coverUrl = book?.getDisplayCover()
    val bitmap by produceState<ImageBitmap?>(null, coverUrl) {
        if (coverUrl.isNullOrEmpty()) return@produceState
        value = loadCoverBitmap(coverUrl)
    }
    Box(modifier) {
        val bmp = bitmap
        if (bmp != null) {
            // 模糊封面铺满背景区域
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(24.dp),
                contentScale = ContentScale.Crop,
            )
            // accent 半透明遮罩, 降低封面饱和度使其作为背景不抢眼
            Box(Modifier.fillMaxSize().background(Color(0xFF165DFF).copy(alpha = 0.15f)))
        } else {
            // 加载中/失败: 原占位色 (与桌面端 DesktopBlurCoverBg 视觉一致)
            Box(Modifier.fillMaxSize().background(Color(0xFF165DFF).copy(alpha = 0.15f)))
        }
    }
}

/**
 * 详情页封面图 (对应桌面端 DesktopBookCover.InfoCover)。
 *
 * - 加载成功: 封面图 [ContentScale.Crop] 填满 + 4dp 圆角裁剪
 * - 加载中/失败: 回退到原占位 (Box + 书名首字 + accent 底, 与桌面端视觉一致)
 *
 * @param book 当前书籍 (可能为 null, null 时走占位)
 * @param modifier 由 shared coverSlot 传入的尺寸约束 (保持原占位布局)
 */
@Composable
fun IosInfoCover(book: Book?, modifier: Modifier = Modifier) {
    val coverUrl = book?.getDisplayCover()
    val bitmap by produceState<ImageBitmap?>(null, coverUrl) {
        if (coverUrl.isNullOrEmpty()) return@produceState
        value = loadCoverBitmap(coverUrl)
    }
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = book?.name,
            modifier = modifier.clip(RoundedCornerShape(4.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        // 兜底: 原占位 (Box + 书名首字 + accent 底, 保持与桌面端视觉一致)
        Box(
            modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF165DFF)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = book?.name?.firstOrNull()?.toString() ?: "?",
                color = Color.White,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * 简介内整宽插图 (对应桌面端 DesktopBookCover.IntroImage)。
 *
 * - 加载成功: 图片 [ContentScale.Crop] 填满 + 4dp 圆角 + 120dp 高 (与桌面端占位尺寸一致)
 * - 加载中/失败: 回退到原占位 (Box + "图片: src" 文本, 与桌面端视觉一致)
 *
 * @param src 图片 URL/路径 (简介内 `<img src="...">` 的值, 通常为网络绝对 URL)
 * @param modifier 外部尺寸约束 (默认 Modifier, 内部仍 fillMaxWidth + 120dp 保持原视觉)
 * @param onClick 点击回调 (供后续接入大图查看器, 当前暂未挂到节点上)
 */
@Composable
fun IosIntroImage(src: String, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    val bitmap by produceState<ImageBitmap?>(null, src) {
        if (src.isBlank()) return@produceState
        value = loadCoverBitmap(src)
    }
    val baseModifier = modifier
        .fillMaxWidth()
        .height(120.dp)
        .clip(RoundedCornerShape(4.dp))
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            modifier = baseModifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        // 兜底: 原文本占位 (保持与桌面端视觉一致)
        Box(
            baseModifier.background(Color(0xFFEEEEEE)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "图片: $src",
                color = Color(0xFF666666),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
    // onClick 暂未挂到节点 (后续接入大图查看器时挂 combinedClickable)
    @Suppress("UNUSED_PARAMETER")
    val _unused = onClick
}

/**
 * 全局封面内存缓存 (与桌面端 DesktopBookCover 的 coverCache 一致)。
 *
 * - key: 封面 URL/路径 (与 produceState 的 key 一致)
 * - value: 已解码 [ImageBitmap] (Compose 可直接渲染)
 *
 * 线程安全: 用 [synchronized] 块包装读写 (Kotlin/Native 新内存模型下可用,
 * 与桌面端 `java.util.Collections.synchronizedMap` 行为等价)。
 * 下载在 IO Dispatcher, 读取在 UI 线程, 仅做引用读写无并发问题。
 *
 * TODO: 未做 LRU 淘汰, 长时间运行可能占用较多内存; 后续可改为限制条目数
 * (参照 app 端 Glide 的内存缓存策略)。
 */
private val coverCache: MutableMap<String, ImageBitmap> = mutableMapOf()

/**
 * 加载封面为 [ImageBitmap] (本地路径或网络 URL), 命中内存缓存直接返回。
 *
 * - `file://` / 绝对路径 `/...`: [UIImage(contentsOfFile:)] 加载, 转 PNG bytes 后 Skia 解码
 * - `http://` / `https://`: [OkHttpClientProviders] 下载字节流, [UIImage(data:)] 验证后 Skia 解码
 * - 相对路径/未知协议: 返回 null (调用方走占位)
 * - 任意步骤异常 (IO/解码/网络): 返回 null (调用方走占位)
 *
 * 参照:
 * - 本地加载: 桌面端 [io.legado.desktop.ui.component.DesktopBookCover] 的 loadCoverBitmap
 * - 网络下载: shared 端 JvmBookImageStorage.saveImages 的 OkHttp GET 逻辑 (iOS 端 KmpHttpClient 等价)
 */
private suspend fun loadCoverBitmap(src: String): ImageBitmap? {
    // 命中内存缓存直接返回 (避免重复下载/解码)
    synchronized(coverCache) {
        coverCache[src]?.let { return it }
    }
    return withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = when {
                src.startsWith("file://") -> {
                    val path = src.removePrefix("file://")
                    decodeLocalImage(path)
                }
                src.startsWith("/") -> {
                    decodeLocalImage(src)
                }
                src.startsWith("http://") || src.startsWith("https://") -> {
                    downloadAndDecode(src)
                }
                else -> null // 相对路径/未知协议, 走占位
            }
            if (bitmap != null) {
                synchronized(coverCache) {
                    coverCache[src] = bitmap
                }
            }
            bitmap
        }.getOrNull()
    }
}

/**
 * 本地路径封面加载 + 解码。
 *
 * 用 [UIImage(contentsOfFile:)] 加载 (任务要求, 利用 iOS 系统级图片解码能力, 支持 HEIC/WebP 等),
 * 再 [UIImagePNGRepresentation] 转 PNG bytes, 最后 Skia [SkiaImage.makeFromEncoded] 转 [ImageBitmap]
 * (因 Compose Multiplatform iOS 无 UIImage → ImageBitmap 直接桥接, 经 PNG bytes 中转)。
 *
 * 失败 (文件不存在/损坏/不支持格式) 返回 null, 调用方走占位。
 */
private fun decodeLocalImage(path: String): ImageBitmap? {
    val image = UIImage(contentsOfFile = path) ?: return null
    return uiImageToBitmap(image)
}

/**
 * UIImage → [ImageBitmap] 转换。
 *
 * 1. [UIImagePNGRepresentation] 把 UIImage 编码为 PNG NSData (无论原格式, 统一转 PNG)
 * 2. [NSData.toByteArray] 把 NSData 拷贝为 Kotlin ByteArray
 * 3. [SkiaImage.makeFromEncoded] 把 PNG bytes 解码为 Skia Image
 * 4. [toComposeImageBitmap] 把 Skia Image 转为 Compose [ImageBitmap]
 *
 * 失败返回 null, 调用方走占位。
 */
private fun uiImageToBitmap(image: UIImage): ImageBitmap? {
    val nsData = UIImagePNGRepresentation(image) ?: return null
    val bytes = nsData.toByteArray()
    if (bytes.isEmpty()) return null
    return runCatching {
        SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull()
}

/**
 * 网络封面下载 + 解码 (参照桌面端 DesktopBookCover.downloadAndDecode)。
 *
 * 用项目已注册的 [OkHttpClientProviders] 取 KmpHttpClient 同步 GET (iOS 端实际为 Ktor CIO 包装,
 * API 与 OkHttp 一致: [KmpRequestBuilder] 构造请求, [KmpHttpClient.newCall].execute() 同步执行),
 * 拿到字节流后 [UIImage(data:)] 验证图片有效性 (任务要求), 再经 PNG bytes + Skia 解码为 [ImageBitmap];
 * 失败返回 null (调用方走占位)。
 */
private fun downloadAndDecode(url: String): ImageBitmap? {
    val client = OkHttpClientProviders.get().okHttpClient
    val request = KmpRequestBuilder().url(url).get().build()
    return runCatching {
        val response = client.newCall(request).execute()
        try {
            if (!response.isSuccessful) return@runCatching null
            val bytes = response.body.bytes()
            if (bytes.isEmpty()) return@runCatching null
            // UIImage(data:) 验证图片有效性 (任务要求, 同时利用 iOS 系统解码支持多格式)
            val nsData = NSData.create(bytes)
            val image = UIImage(data = nsData) ?: return@runCatching null
            uiImageToBitmap(image)
        } finally {
            // iOS 端 KmpResponse.close() 是 no-op (body 已读入内存), 调用以保持资源管理习惯
            response.close()
        }
    }.getOrNull()
}

/**
 * NSData → ByteArray 转换 (Kotlin/Native 无标准库扩展, 自行实现)。
 *
 * 用 [NSData.bytes] (CPointer) + [kotlinx.cinterop.readBytes] 拷贝到 ByteArray,
 * 与 [io.legado.app.help.book.BookStorage.ios.kt] 的 NSData.toByteArray 实现一致。
 * 空数据 (length=0) 直接返回空数组。
 */
private fun NSData.toByteArray(): ByteArray {
    val size = this.length.toInt()
    if (size == 0) return ByteArray(0)
    // bytes 是 CPointer<ByteVar>, readBytes 是 kotlinx.cinterop 扩展, 自动拷贝 size 字节
    return this.bytes?.readBytes(size) ?: ByteArray(0)
}
