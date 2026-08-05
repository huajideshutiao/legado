package io.legado.app.ui.book.read.page

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.legado.app.constant.AppLog
import io.legado.app.help.config.ReadBookConfigProviders
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.image.ImageBitmapLoader
import io.legado.app.ui.book.read.ReadBookEvents
import io.legado.app.ui.book.read.ReadConfigChange
import io.legado.app.ui.book.read.page.ReaderBackgroundImageCache.FAIL_RETRY_INTERVAL_MS
import io.legado.app.ui.book.read.page.ReaderBackgroundImageCache.version
import io.legado.app.utils.systemCurrentTimeMillis
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 阅读背景图的进程内小型缓存。
 *
 * 翻页委托会同时绘制当前页、上一页/下一页以及仿真翻页的背面；背景图不能
 * 由某一个页面实例临时加载，否则动画中某一层会先显示透明窗口背景。缓存按
 * 配置地址复用位图，并用 [version] 在异步加载完成后只触发绘制层重绘。
 */
object ReaderBackgroundImageCache {

    private const val MAX_ENTRIES = 4

    /** 失败冷却: 同一地址失败后 [FAIL_RETRY_INTERVAL_MS] 内不再请求, 之后允许重试 */
    private const val FAIL_RETRY_INTERVAL_MS = 60_000L

    private val lock = SynchronizedObject()
    private val bitmaps = LinkedHashMap<String, ImageBitmap>()
    private val failedAt = HashMap<String, Long>()
    private val inFlight = HashSet<String>()
    private val scope = CoroutineScope(SupervisorJob() + IoDispatcher)

    /** Canvas 读取此状态建立订阅，背景图加载完成后自动刷新所有页面层。 */
    var version by mutableIntStateOf(0)
        private set

    /** 已加载背景图；命中时移到 LRU 队尾。 */
    fun peek(source: String): ImageBitmap? = synchronized(lock) {
        bitmaps.remove(source)?.also { bitmaps[source] = it }
    }

    /** 发起一次后台加载；同一地址不会并发重复请求，失败后冷却期内不重试（超时自动恢复）。 */
    fun requestAsync(source: String) {
        if (source.isBlank()) return
        synchronized(lock) {
            val now = systemCurrentTimeMillis()
            if (bitmaps.containsKey(source) ||
                !inFlight.add(source) ||
                failedAt[source]?.let { now - it < FAIL_RETRY_INTERVAL_MS } == true
            ) {
                return
            }
        }
        scope.launch {
            val bitmap = runCatching {
                ImageBitmapLoader().loadBitmap(source, book = null, bookSource = null)
            }.onFailure {
                AppLog.put("阅读背景图加载失败 $source\n${it.message}", it)
            }.getOrNull()
            synchronized(lock) {
                inFlight.remove(source)
                if (bitmap == null) {
                    failedAt[source] = systemCurrentTimeMillis()
                } else {
                    failedAt.remove(source)
                    bitmaps.remove(source)
                    bitmaps[source] = bitmap
                    while (bitmaps.size > MAX_ENTRIES) {
                        bitmaps.entries.iterator().apply {
                            if (hasNext()) {
                                next()
                                remove()
                            }
                        }
                    }
                    // 取色回写: 非 Android 端没有 Drawable 取色链路 (原版 upBg), bgMeanColor 恒为 0,
                    // 图片背景时 curBgColor 兜底成不透明白/黑。这里统一在加载成功后取底部 20% 区域
                    // 代表色写回 (对照原版 getRepresentativeColor + 各通道 +3 钳制), 并刷新一次样式:
                    // (菜单顶/底栏取色增强 2026-08-06 已移除, 控制层回落主题色)
                    // 仅当该源仍是当前生效背景时才写回, 避免快速切换时旧图覆盖新配置。
                    runCatching {
                        val cfg = ReadBookConfigProviders.get().config
                        if (source == cfg.curBgImageSource()) {
                            cfg.bgMeanColor = bitmap.regionMeanColor(0.8f, 1f)
                            ReadBookEvents.postConfig(ReadConfigChange.BG)
                        }
                    }
                }
                // 即使失败也刷新一次，让 Canvas 从 loading 状态转为稳定的底色占位。
                // 与缓存更新共用锁，避免多个背景并发完成时丢掉 version 自增。
                version++
            }
        }
    }

    /** 配置切换时可主动清除旧图；当前地址下次绘制会重新加载。 */
    fun clear() {
        synchronized(lock) {
            bitmaps.clear()
            failedAt.clear()
            version++
        }
    }

    /**
     * 清除指定地址的失败冷却记录。
     * 用户重选同一预设时由调用方主动调用，避免 60s 冷却吞掉重试
     * (重选同一背景 source 不变, LaunchedEffect 不会重启, 只能靠主动请求恢复)。
     */
    fun clearFailed(source: String) {
        synchronized(lock) {
            failedAt.remove(source)
        }
    }
}

/**
 * 取图片指定纵向区间 [topRatio, bottomRatio] 的平均代表色（ARGB）。
 *
 * 对照原版 `upBg` 的取色语义: `Bitmap.getRepresentativeColor(0, 区间起点, 宽, 区间高)`
 * 平均色 + 各通道 +3 钳制到 255。大图按行抽样 (最多 32 行) 控制读像素开销
 * (2026-08-06 用户拍板: 上下各取 20%, 不用取太多, 权衡效果与性能)。
 */
internal fun ImageBitmap.regionMeanColor(topRatio: Float, bottomRatio: Float): Int {
    val w = width
    val h = height
    if (w <= 0 || h <= 0) return 0
    val cropTop = (h * topRatio).toInt().coerceIn(0, h - 1)
    val cropBottom = (h * bottomRatio).toInt().coerceIn(cropTop + 1, h)
    val cropHeight = cropBottom - cropTop
    val rowStep = (cropHeight / 32).coerceAtLeast(1)
    val rows = cropHeight / rowStep
    val buffer = IntArray(w)
    var r = 0L
    var g = 0L
    var b = 0L
    var n = 0L
    var y = cropTop
    var i = 0
    while (i < rows) {
        readPixels(buffer, 0, y, w, 1, 0, w)
        var x = 0
        while (x < w) {
            val c = buffer[x]
            r += c shr 16 and 0xFF
            g += c shr 8 and 0xFF
            b += c and 0xFF
            x++
        }
        n += w
        y += rowStep
        i++
    }
    if (n == 0L) return 0
    fun clamp(v: Long) = ((v / n) + 3).coerceAtMost(255L).toInt()
    return 0xFF000000.toInt() or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
}

/** 以中心裁剪方式把背景图铺满当前 DrawScope。 */
internal fun DrawScope.drawReaderBackgroundBitmap(
    bitmap: ImageBitmap,
    alpha: Float,
) {
    if (bitmap.width <= 0 || bitmap.height <= 0 || size.width <= 0f || size.height <= 0f) return
    val scale = maxOf(size.width / bitmap.width, size.height / bitmap.height)
    val dstWidth = bitmap.width * scale
    val dstHeight = bitmap.height * scale
    drawImage(
        image = bitmap,
        dstOffset = IntOffset(
            ((size.width - dstWidth) / 2f).toInt(),
            ((size.height - dstHeight) / 2f).toInt(),
        ),
        dstSize = IntSize(
            dstWidth.toInt().coerceAtLeast(1),
            dstHeight.toInt().coerceAtLeast(1),
        ),
        alpha = alpha.coerceIn(0f, 1f),
    )
}
