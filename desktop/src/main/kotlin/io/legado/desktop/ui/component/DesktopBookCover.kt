package io.legado.desktop.ui.component

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
import io.legado.app.help.http.OkHttpClientProviders
import io.legado.app.ui.compose.platform.rememberString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/**
 * 桌面端 BookCover 加载组件 (替代 BookInfoScreen 中的占位实现)。
 *
 * # 加载策略
 *
 * - **本地路径** (`file://` 或绝对路径 `/...`): 用 [ImageIO.read] 加载为 [ImageBitmap]
 *   (与 [io.legado.desktop.ui.bookshelf.BookshelfScreen] 的 loadLocalImageBitmap 思路一致,
 *   零外部依赖; 桌面端 epub/pdf 等本地书籍封面经 FileBook.getCoverPath 提取后由此读取)
 * - **网络路径** (`http://`/`https://`): 用项目已注册的 OkHttp (经 [OkHttpClientProviders])
 *   下载字节流后 ImageIO 解码 (参照 shared 端 JvmBookImageStorage.saveImages 的下载逻辑,
 *   不引入 Glide/Coil 等新库)
 * - **内存缓存**: 全局 [coverCache] 缓存已解码 [ImageBitmap], 避免详情页内多次重组时
 *   重复下载/解码 (BlurCoverBg 与 InfoCover 共享同一 Book 的封面, 命中缓存零开销)
 * - **加载失败/进行中**: 走原占位视觉 (保持与替换前一致的兜底样式)
 *
 * # 提供的 Composable
 *
 * - [DesktopBlurCoverBg]: 详情页顶部模糊封面背景 (替代 app 端 Glide + BookInfoBgTransformation)
 * - [DesktopInfoCover]: 详情页封面图 (替代 app 端 AndroidView + CoverImageView + Glide)
 * - [DesktopIntroImage]: 简介内整宽插图 (替代 app 端 Glide + CustomTarget<Bitmap>)
 *
 * # 约束
 *
 * - 不引入 Glide/Coil 等新库, 仅用 JDK ImageIO + 项目已有 OkHttp
 * - 不修改 shared 模块 slot 契约
 * - 不擅自修改 ui 样式 (宽高边距与原占位保持一致)
 */
object DesktopBookCover {

    /**
     * 详情页顶部模糊封面背景。
     *
     * - 加载成功: 封面图 [ContentScale.Crop] 铺满 + [blur] 模糊 + accent 半透明遮罩
     *   (与 app 端 BookInfoBgTransformation 的模糊+降饱和视觉效果接近)
     * - 加载中/失败: 回退到原纯色占位 `Color(0xFF165DFF).copy(alpha = 0.15f)`
     *
     * @param book 当前书籍 (可能为 null, 取 [Book.getDisplayCover] 得封面 URL)
     * @param modifier 由 shared blurCoverBgSlot 传入的尺寸约束 (保持原占位布局)
     */
    @Composable
    fun BlurCoverBg(book: Book?, modifier: Modifier = Modifier) {
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
                // 加载中/失败: 原占位色 (与替换前 DesktopBlurCoverBg 视觉一致)
                Box(Modifier.fillMaxSize().background(Color(0xFF165DFF).copy(alpha = 0.15f)))
            }
        }
    }

    /**
     * 详情页封面图。
     *
     * - 加载成功: 封面图 [ContentScale.Crop] 填满 + 4dp 圆角裁剪
     * - 加载中/失败: 回退到原占位 (Box + 书名首字 + accent 底, 与替换前视觉一致)
     *
     * @param book 当前书籍 (可能为 null, null 时走占位)
     * @param modifier 由 shared coverSlot 传入的尺寸约束 (保持原占位布局)
     */
    @Composable
    fun InfoCover(book: Book?, modifier: Modifier = Modifier) {
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
            // 兜底: 原占位 (Box + 书名首字 + accent 底, 保持替换前视觉)
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
     * 简介内整宽插图。
     *
     * - 加载成功: 图片 [ContentScale.Crop] 填满 + 4dp 圆角 + 120dp 高 (与原占位尺寸一致)
     * - 加载中/失败: 回退到原占位 (Box + "图片: src" 文本, 保持替换前视觉)
     *
     * @param src 图片 URL/路径 (简介内 <img src="..."> 的值, 通常为网络绝对 URL)
     * @param modifier 外部尺寸约束 (默认 Modifier, 内部仍 fillMaxWidth + 120dp 保持原视觉)
     * @param onClick 点击回调 (供后续接入大图查看器, 当前暂未挂到节点上)
     */
    @Composable
    fun IntroImage(src: String, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
        val imageLabelTemplate = rememberString("image_label_with_src")
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
            // 兜底: 原文本占位 (保持替换前视觉)
            Box(
                baseModifier.background(Color(0xFFEEEEEE)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = imageLabelTemplate.format(src),
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
}

/**
 * 全局封面内存缓存。
 *
 * - key: 封面 URL/路径 (与 produceState 的 key 一致)
 * - value: 已解码 [ImageBitmap] (Compose 可直接渲染)
 *
 * 线程安全: 用 [java.util.Collections.synchronizedMap] 包装, 下载在 IO Dispatcher,
 * 读取在 UI 线程, 仅做引用读写无并发问题。
 *
 * TODO: 未做 LRU 淘汰, 长时间运行可能占用较多内存; 后续可改为 androidx.collection.LruCache
 * 或限制条目数 (参照 app 端 Glide 的内存缓存策略)。
 */
private val coverCache: MutableMap<String, ImageBitmap> =
    java.util.Collections.synchronizedMap(mutableMapOf())

/**
 * 加载封面为 [ImageBitmap] (本地路径或网络 URL), 命中内存缓存直接返回。
 *
 * - `file://` / 绝对路径 `/...`: [ImageIO.read] 读文件
 * - `http://` / `https://`: [OkHttpClientProviders] 下载字节流后 [ImageIO.read] 解码
 * - 相对路径/未知协议: 返回 null (调用方走占位)
 * - 任意步骤异常 (IO/解码/网络): 返回 null (调用方走占位)
 *
 * 参照:
 * - 本地加载: [io.legado.desktop.ui.bookshelf.BookshelfScreen] 的 loadLocalImageBitmap
 * - 网络下载: shared 端 JvmBookImageStorage.saveImages 的 OkHttp GET 逻辑
 */
private suspend fun loadCoverBitmap(src: String): ImageBitmap? {
    // 命中内存缓存直接返回 (避免重复下载/解码)
    coverCache[src]?.let { return it }
    return withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = when {
                src.startsWith("file://") -> {
                    val file = File(src.removePrefix("file://"))
                    if (!file.exists()) null else ImageIO.read(file)?.toComposeImageBitmap()
                }
                src.startsWith("/") -> {
                    val file = File(src)
                    if (!file.exists()) null else ImageIO.read(file)?.toComposeImageBitmap()
                }
                src.startsWith("http://") || src.startsWith("https://") -> {
                    downloadAndDecode(src)
                }
                else -> null // 相对路径/未知协议, 走占位
            }
            if (bitmap != null) {
                coverCache[src] = bitmap
            }
            bitmap
        }.getOrNull()
    }
}

/**
 * 网络封面下载 + 解码 (参照 JvmBookImageStorage.saveImages 的下载逻辑)。
 *
 * 用项目已注册的 [OkHttpClientProviders] 取 OkHttpClient 同步 GET, 拿到字节流后
 * [ImageIO.read] 解码为 [ImageBitmap]; 失败返回 null (调用方走占位)。
 */
private fun downloadAndDecode(url: String): ImageBitmap? {
    val client = OkHttpClientProviders.get().okHttpClient
    val request = Request.Builder().url(url).build()
    return runCatching {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val bytes = response.body?.bytes() ?: return@use null
            ImageIO.read(ByteArrayInputStream(bytes))?.toComposeImageBitmap()
        }
    }.getOrNull()
}
