package io.legado.app.ui.book.info

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.legado.app.data.entities.Book
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.help.image.resourceIdentity
import io.legado.app.ui.root.PlatformCapabilityProviders

/**
 * 模糊封面背景实现 (desktop/iOS/鸿蒙三端共享, 安卓另有 [io.legado.app.ui.book.info.BookInfoBlurCoverBg])。
 *
 * 走 [BookImageLoaders] (各端注入自己的图片加载实现) 加载封面 Bitmap。模糊两条路:
 * 绘制期模糊生效的端直接叠 [Modifier.blur]; 不生效的端 (Android 12 以下) 由加载层
 * 先算好模糊位图 (BookImageLoader.blurForBackground), 绘制期不再叠空操作的修饰符。
 *
 * 三个阶段分开归属: 图片请求只取未变换的工作图 (容器尺寸不参与身份, 也不做尺寸匹配);
 * CPU 模糊只随真实工作图与模糊半径变化算一次; 压暗与渐变蒙版在绘制期按当前容器叠。
 * 容器微变 (窗口 resize / 分屏) 只重绘, 不重请求也不重糊。
 * 加载中/失败/E-Ink 模式不绘制 (对照原版 bgBook 空 drawable), 不铺色块以免换图时闪一下。
 *
 * 加载成功后把**原图**位图上报给 [LocalCoverLoaded], 详情页宿主据此就地对封面采样取色 ——
 * 取色与背景共用同一次加载, 不另发请求。
 *
 * 渐变蒙版 (与 app 端详情页背景同一曲线):
 * 先叠压暗 (原版 argb(50,0,0,0) SRC_ATOP ≈ 压暗 20%), 再按 DstIn alpha 蒙版收尾
 * (顶部 30% 清晰 → 30%-65% 柔和过渡 → 底部透明)。用 Compose 绘制层实现, 免 Android 位图 API。
 *
 * @param book 当前书籍 (可能为 null)
 * @param coverTick 封面重载 key (变更时重新加载)
 * @param inBookshelf 是否在书架 (本简化实现未使用, 保留签名对齐 slot 契约)
 * @param isEInkMode E-Ink 模式跳过图片加载 (不绘制)
 * @param modifier 调用方传入的尺寸约束
 */
/** 背景模糊半径 (dp): 绘制期模糊与被预模糊位图共用同一值 */
private val COVER_BG_BLUR_RADIUS = 24.dp

@Composable
fun SharedBlurCoverBg(
    book: Book?,
    coverTick: Int,
    inBookshelf: Boolean,
    isEInkMode: Boolean,
    modifier: Modifier,
    land: Boolean = false,
) {
    val cover = book?.getDisplayCover()
    // 四端都在宿主根注册了实现, 取不到即配置错误: 用 get() 直接报错, 不静默不画
    val loader = remember { BookImageLoaders.get() }
    // 工作图 (未变换原图) 与容器无关: 容器微变不重建请求, 也不重算模糊。
    // 与封面 url 同生命周期: 换 url 立即作废, 同时结束旧图结果的生命周期;
    // 同 url 重载期间保留旧图, 失败也不清空 —— 该 url 曾被 403 时失败跳过表会直接拦下重载,
    // 清空就闪回空白
    var work by remember(cover) { mutableStateOf<ImageBitmap?>(null) }
    // 模糊背景是否已绘制 (宿主与绘制方共用同一状态, 见 shared BookCoverPalette)
    val paintedState = LocalBlurCoverPainted.current
    // 封面取色入口 (详情页宿主提供): 把刚解出的原图交上去就地采样, 不再另发一次加载
    val onCoverLoaded = LocalCoverLoaded.current
    // 框架的模糊修饰符在 Android 12 以下不生效: 这些端改为加载后一次性算好模糊位图
    val drawBlur = PlatformCapabilityProviders.get().drawBlurSupported
    val blurRadiusPx = with(LocalDensity.current) { COVER_BG_BLUR_RADIUS.toPx().roundToInt() }
    val blurModifier = if (drawBlur) Modifier.blur(COVER_BG_BLUR_RADIUS) else Modifier
    // 请求身份只含资源 (url/来源/重载 key), 不含容器尺寸: 无尺寸约定下库按自身能力复用工作图
    LaunchedEffect(cover, coverTick, loader, isEInkMode, book?.origin) {
        val b = book ?: return@LaunchedEffect
        if (isEInkMode || cover.isNullOrBlank()) return@LaunchedEffect
        val loaded = loader.loadImageOrNull(cover, b.origin) ?: return@LaunchedEffect
        work = loaded
        // 取色必须用原图: 模糊图的像素均值会让详情页主色发浑
        onCoverLoaded(loaded)
    }
    // 显示位按封面 url 的生命周期持有已就绪结果: 换 url 立即作废。
    // 同 url 的新作业 (新底层图/半径变) 在完成前不动它 —— 旧图留到新的最终可绘制图算完再交接。
    var ready by remember(cover) { mutableStateOf<ImageBitmap?>(null) }
    // CPU 作业身份是真实底层图 + 半径: 同一解码结果换包装不重算;
    // 作业被新身份顶掉时旧作业随标准 Job 取消
    val workIdentity = work?.resourceIdentity()
    LaunchedEffect(workIdentity, blurRadiusPx, drawBlur) {
        val source = work ?: return@LaunchedEffect
        val result = if (drawBlur) source else withContext(Dispatchers.Default) {
            loader.blurForBackground(source, blurRadiusPx)
        } ?: source
        ready = result
    }
    val bitmap = ready
    // 写出时机与绘制条件一致 (本实现画出封面的充要条件就是 bitmap 非空)。
    // 共享状态在组合后写回, 顶栏读到新值会多重组一帧 —— 背景已画出而顶栏尚未换色的那一帧
    // 是主题色压在模糊封面上, 不存在"白字压在空白背景上"的可读性缺口
    SideEffect { paintedState.value = bitmap != null }
    Box(modifier) {
        // 模糊封面铺满 + 压暗 + 渐变蒙版 (与 app 端详情页背景同一曲线)
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // 链序关键: drawWithContent 在 blur 外层, 否则渐变蒙版/压暗也会被高斯模糊
                modifier = Modifier
                    .fillMaxSize()
                    .bookInfoBgOverlay(land)
                    .then(blurModifier),
            )
        }
        // 加载中/失败/E-Ink 一律不画东西 (对照原版 bgBook: drawable 为空即空白, 不铺色块),
        // 铺深色占位再换成模糊图会闪一下
    }
}

/**
 * 详情页背景的压暗 + 渐变蒙版 (四端共用这一份曲线)。
 *
 * 压暗层用 [BlendMode.SrcAtop]: 只压图自身、不改图片 alpha, 封面透明处不会把
 * 身后页面底色一起压暗。
 * 链序: 必须挂在自己开离屏缓冲的绘制层上, DstIn 蒙版否则会作用到父画布 (含身后页面底色),
 * 底部被抹成透明后露出窗口黑底。
 *
 * @param land 横屏只保留均匀压暗: 左半列整列铺满时任何方向的"一边黑"渐变都不合适
 */
fun Modifier.bookInfoBgOverlay(land: Boolean): Modifier =
    this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            // 压暗层: SrcAtop 只压图自身、不改 alpha, 必须在蒙版之前叠
            drawRect(color = Color(0x32000000), blendMode = BlendMode.SrcAtop)
            if (!land) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Black,
                            0.30f to Color.Black,
                            0.48f to Color(0xD2000000),
                            0.63f to Color(0xA5000000),
                            0.77f to Color(0x6E000000),
                            0.90f to Color(0x1E000000),
                            1.00f to Color.Transparent,
                        ),
                    ),
                    blendMode = BlendMode.DstIn,
                )
            }
        }
