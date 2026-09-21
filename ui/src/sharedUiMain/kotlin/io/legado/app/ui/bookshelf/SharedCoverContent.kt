package io.legado.app.ui.bookshelf

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import io.legado.app.help.config.AppConfigProviders
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.image.BookImageLoaders
import io.legado.app.help.image.DecodedBitmapCache
import io.legado.app.model.BookCoverShared
import io.legado.app.model.BookCoverShared.CoverRatio
import io.legado.app.model.BookCoverShared.DefaultCoverEntry
import io.legado.app.model.defaultCoverDisplayPath
import io.legado.app.ui.compose.component.DefaultCoverNineImage
import io.legado.app.ui.compose.component.NinePatchImageOrImage
import io.legado.app.ui.compose.theme.AppTheme
import io.legado.app.ui.compose.theme.AppTheme.DesignTokens
import io.legado.app.ui.root.PhotoSharedCoverHost
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * 封面渲染管线 (书籍与分组共用一份实现)。
 *
 * 原 View 版把这条规则写在唯一实现里 (`CoverImageView.onMeasure` + `BookCover.load`), Compose
 * 版曾按书籍/分组各写一份, 两侧尺寸链与加载分支随后各自漂移。此处收成单一入口, 数据差异由
 * [CoverSource] 承载。
 *
 * 加载语义:
 * - 默认封面链: 用户图集非空时按 seed 稳定选一张烘焙图, 图集为空回落内置 `image_cover_default`
 * - 真封面与占位并发; 失败/无 path/[AppConfigAccessor.useDefaultCover] 时停在默认封面
 * - 占位位图经 [DecodedBitmapCache] 跨条目共享, 不再为占位重复走图片管线
 *
 * 尺寸语义: 见 [Modifier.coverSizeModifier]。
 *
 * @param source 被渲染的封面数据 (书籍或分组)
 * @param modifier 外部尺寸约束 (调用方给宽/高/内边距等); 本组件只追加比例与圆角
 * @param isVideoCover 是否 16:9 (true) / 3:4 (false), 对照 `BookCover.CoverRatio`
 * @param reloadTick 封面重载信号 (configTick): 变化时重启加载, 不变不额外触发
 * @param nameAuthorOverlay 默认封面上的竖排书名/作者叠字 (分组封面无书名, 不叠)
 */
@Composable
internal fun SharedCoverContent(
    source: CoverSource,
    modifier: Modifier,
    isVideoCover: Boolean,
    reloadTick: Int,
    nameAuthorOverlay: Boolean,
) {
    val loader = remember { BookImageLoaders.getOrNull() }
    // useDefaultCover 时跳过网络加载, 直接走默认封面链 (对照原 View 版封面组件 load 行为);
    // 每次组合读 prefs (不 remember): 宿主重组触发 LaunchedEffect 重启时读到的是最新配置
    val useDefaultCover = AppConfigProviders.get().useDefaultCover
    // 首帧即出真图: 同一张封面已在别处 (书架格子/列表/上一次详情) 解码过时, 组合期同步取回,
    // 不再让首帧摆默认封面——共享元素飞的是"可见那一端"的内容, 详情页首帧占位会被放大到整个
    // 飞行尺寸, 观感即"闪一下默认封面"。小表同 url 只留面积最大的一档
    // (见 [DecodedBitmapCache.recordCover]), 取回的是迄今解过的最大那份;
    // 若它仍比当前显示尺寸小, 会先糊一帧, 下面按自己尺寸解完替换。
    val cachedCover = remember(source.coverUrl, source.cacheKey, useDefaultCover) {
        if (!source.shareDecodedCover || useDefaultCover || source.coverUrl.isNullOrBlank()) null
        else DecodedBitmapCache.peekCover(source.coverUrl)
    }
    // 位图与"是否默认封面"合成一个 state: 一次加载只引发一次重组
    var coverState by remember(source.coverUrl, source.cacheKey) {
        mutableStateOf(cachedCover?.let { CoverBitmap(it, false) } ?: NoCoverBitmap)
    }
    // 尺寸只用于首次按显示大小降采样；后续窗口 resize 不应重新发起封面请求。
    // 否则每跨过一个量化尺寸档都会再次进入图片 Interceptor，重复执行书源 JS header 规则。
    val displaySize = remember { MutableStateFlow(IntSize.Zero) }
    LaunchedEffect(source.coverUrl, source.cacheKey, loader, useDefaultCover, isVideoCover, reloadTick) {
        if (loader == null) return@LaunchedEffect
        val decodeSize = firstValidCoverDecodeSize(displaySize)
        val ratio = if (isVideoCover) CoverRatio.VIDEO else CoverRatio.NOVEL

        // 默认封面链要读 prefs + 解 JSON (解析已按 raw 串记忆化), 挪到协程内真用得上时再算。
        // 解码结果进 [DecodedBitmapCache] 跨条目共享: 旧实现每条封面都完整跑一遍图片管线解
        // 一张占位图 (真封面已命中内存缓存时也要先解占位), 首屏/滚动的请求与解码开销翻倍。
        suspend fun defaultState(): CoverBitmap {
            // 渲染需知 ninePatch 标记, 走 entry 版选图 (defaultCoverFilePath 保留给 AudioPlay 等调用)
            val entry = defaultCoverEntry(seed = source.defaultCoverSeed, ratio = ratio)
                ?: return NoCoverBitmap
            val path = defaultCoverDisplayPath(entry, ratio)
            // reloadTick 并入 key: 封面重载信号变了不得复用旧位图 (重烘焙/换图集后路径可能不变)
            val key = DecodedBitmapCache.cacheKey(
                "$path#$reloadTick", null, isCover = true,
                widthPx = decodeSize.width, heightPx = decodeSize.height,
            )
            DecodedBitmapCache.get(key)?.let { return CoverBitmap(it, true, entry.ninePatch) }
            val bmp = loader.loadImageOrNull(path, null, decodeSize.width, decodeSize.height)
                ?: return NoCoverBitmap
            DecodedBitmapCache.put(key, bmp)
            return CoverBitmap(bmp, true, entry.ninePatch)
        }
        val coverUrl = source.coverUrl
        if (useDefaultCover || coverUrl.isNullOrBlank()) {
            coverState = defaultState()
            return@LaunchedEffect
        }
        // 真封面与占位并发 (旧的串行写法让真封面白等一次占位加载)。手上已有真图时不铺占位:
        // 缓存同步命中的首帧、以及 reloadTick 重载期间的上一张真图, 都一直显示到新图就绪
        // (对照原版 ImageView 加载期间保留上一帧 drawable); 确实无图可显示才铺默认封面消空白。
        coroutineScope {
            val real = async {
                if (source.persistentCover) {
                    // 书架书封面落持久区: 书源失效后封面不可重获, 清缓存不得把它清掉
                    loader.loadCoverOrNull(coverUrl, source.origin, decodeSize.width, decodeSize.height)
                } else {
                    // 非书架书 (搜索/发现/主页结果) 只落临时区, 不占书架持久区
                    loader.loadImageOrNull(coverUrl, source.origin, decodeSize.width, decodeSize.height)
                }
            }
            if (coverState.bitmap == null || coverState.isDefault) {
                coverState = defaultState()
            }
            val bmp = real.await()
            // 失败保持当前图不变 (对照原版 BookCover.load 的 .error(newDefaultDrawable))
            if (bmp != null) {
                if (source.shareDecodedCover) {
                    // 记进封面小表 (预算与解码主缓存分开, 同 url 只留面积最大的一档, 见
                    // [DecodedBitmapCache.recordCover])。不能改从 Coil 内存缓存按 url 现取:
                    // 它的 key 就是 url、不带尺寸, 书架格子/详情大图/歌词栏小图会互相覆写;
                    // 有效性判定与超预算弱引用淘汰都在库内部, 外部手取只能拿到上采样糊图或空;
                    // 安卓端 data 还被换成烘焙 webp 路径、鸿蒙端不注册 BookImageLoaders,
                    // 动图更是永不进内存缓存。这张小表是书架↔详情↔列表间复用已解位图的唯一通道
                    DecodedBitmapCache.recordCover(coverUrl, bmp)
                }
                coverState = CoverBitmap(bmp, false)
            }
        }
    }
    val outerModifier = modifier.coverSizeModifier(isVideoCover)
        .clip(DesignTokens.shapeSm)
        .onSizeChanged { displaySize.value = it }
    val bmp = coverState.bitmap
    val content: @Composable BoxScope.() -> Unit = {
        if (bmp != null && !coverState.isDefault) {
            Image(
                bitmap = bmp,
                contentDescription = source.contentDescription,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            if (bmp != null) {
                // 用户图集里的烘焙图 (已按 ratio 裁好); .9 图按九宫格拉伸
                NinePatchImageOrImage(
                    bitmap = bmp,
                    isNinePatch = coverState.isNinePatch,
                    contentDescription = source.contentDescription,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                // 图集为空 / 读盘失败: 内置默认封面, 运行期 3:4 居中裁剪 + 九宫格拉伸 (四角不变形)
                DefaultCoverNineImage(
                    modifier = Modifier.matchParentSize(),
                    contentDescription = source.contentDescription,
                )
            }
            if (nameAuthorOverlay) {
                CoverNameAuthorOverlay(
                    name = source.title,
                    author = source.author,
                    accent = AppTheme.colors.accent,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }
    // 只有书籍封面有配对端点; 配对 token 由条目层的共享配对身份下发
    // (见 [io.legado.app.ui.root.LocalSharedCoverBinding]), 分组封面无端点
    if (!source.sharedTransition) {
        Box(modifier = outerModifier, content = content)
        return
    }
    // 两套端点 (页转场对 + 大图对) 都在这唯一入口挂: 书架/搜索/发现/详情/音频的封面全部覆盖
    PhotoSharedCoverHost(
        cover = source.coverUrl,
        outerModifier = outerModifier,
        // 圆角要写在共享节点之内, 飞行副本才有圆角
        sharedModifier = Modifier.clip(DesignTokens.shapeSm),
        content = content,
    )
}

/**
 * 封面尺寸链: 由调用方约束的那一个维度按比例反算另一个维度, 对齐原 View 版
 * `CoverImageView.onMeasure` 的两分支 (宽 EXACTLY → 按高推宽; 高 EXACTLY → 按宽推高)。
 *
 * 接收者即调用方给的尺寸约束, 本函数只补比例。不得追加 `fillMaxWidth` 一类把另一个维度也钉死的
 * 修饰符: [aspectRatio] 先试的四个候选 (enforceConstraints=true) 全失败后, 会落到不校验约束的
 * 兜底分支 —— 那里宽度取 maxWidth、高度按比例推, 于是节点实际尺寸 = 行宽 × (行宽/ratio),
 * 高度突破被钉死的高度。列表档因此封面占满整行并向下溢出, 同排文字被挤到 0 宽。
 *
 * [matchHeightConstraintsFirst] 取 true: 与 [SharedBookCover] 及原 View 版"高度已定则按高推宽"
 * 一致。两种顺序只在宽高两维都有界且两个候选都满足约束时才不同, 此时先取高度符合调用方
 * "给多少高度就填满多少高度"的意图; 网格/视频档 maxHeight 无界, 两种顺序结果相同。
 */
private fun Modifier.coverSizeModifier(isVideoCover: Boolean): Modifier =
    aspectRatio(
        ratio = if (isVideoCover) VIDEO_COVER_RATIO else NOVEL_COVER_RATIO,
        matchHeightConstraintsFirst = true,
    )

/**
 * 封面数据源: 把书籍与分组在「加载」维度上的差异收敛成参数。
 *
 * - [coverUrl] / [origin]: 传给 [BookImageLoaders]; 分组无书源来源, [origin] 恒 null
 * - [cacheKey]: 参与 `remember`/`LaunchedEffect` key 的附加维度。书籍需带上 `origin`
 *   (同 URL 不同来源是不同资源), 分组无此维度
 * - [defaultCoverSeed]: 默认封面选图种子 (书籍取书名、无书名回落封面路径; 分组取组名)
 * - [shareDecodedCover]: 是否参与 [DecodedBitmapCache] 的"已解码封面"小表 (组合期同步取回首帧 +
 *   加载成功后登记)。书籍在书架↔详情↔列表间复用同一份位图故开启; 分组封面无跨页复用方
 * - [persistentCover]: 真封面落持久磁盘区 (书架书) 还是临时区 (搜索/发现结果)
 * - [sharedTransition]: 是否挂共享元素转场端点 (仅书籍封面有配对目标)
 * - [title] / [author]: 默认封面叠字内容 ([author] 为 null 表示不叠字, 如分组)
 */
@Immutable
internal data class CoverSource(
    val coverUrl: String?,
    val origin: String?,
    val cacheKey: String?,
    val defaultCoverSeed: String?,
    val shareDecodedCover: Boolean,
    val persistentCover: Boolean,
    val sharedTransition: Boolean,
    val contentDescription: String?,
    val title: String?,
    val author: String?,
)

/** 封面位图 + 是否默认封面 (决定要不要叠竖排书名/作者) + 是否 .9 图 (决定渲染路径) */
@Immutable
internal data class CoverBitmap(
    val bitmap: ImageBitmap?,
    val isDefault: Boolean,
    val isNinePatch: Boolean = false,
)

internal val NoCoverBitmap = CoverBitmap(null, false)

/**
 * 解码目标尺寸: 向上取到 64 的倍数, 让相邻列宽/微小布局抖动共用同一份内存缓存,
 * 也避免尺寸每变一像素就重新解一次。
 */
internal fun coverDecodeSize(size: IntSize): IntSize {
    if (size.width <= 0 || size.height <= 0) return IntSize.Zero
    fun step(px: Int) = (px + 63) / 64 * 64
    return IntSize(step(size.width), step(size.height))
}

/**
 * 等待首个有效布局尺寸并量化，随后立即返回。
 *
 * 图片请求只需要首个显示尺寸来降采样；不能持续 collect 尺寸，否则桌面窗口 resize 会触发
 * 新请求，并让书源的 JS 请求头规则跟着重复执行。
 */
internal suspend fun firstValidCoverDecodeSize(sizes: Flow<IntSize>): IntSize =
    sizes.map(::coverDecodeSize).first { it != IntSize.Zero }

/** 封面宽高比 (宽/高); 对照 BookCoverShared.CoverRatio: NOVEL=3:4 → 0.75 */
internal const val NOVEL_COVER_RATIO = 3f / 4f

/** 封面宽高比 (宽/高); 对照 BookCoverShared.CoverRatio: VIDEO=16:9 → 1.78 */
internal const val VIDEO_COVER_RATIO = 16f / 9f

/**
 * 用户自定义默认封面集选出的 entry (对照 app 端 `BookCover.newDefaultDrawable` 的选图段)。
 *
 * 图集为空时返回 null, 调用方回落内置图; [DefaultCoverEntry.ninePatch] 供渲染端决定
 * 是否走九宫格拉伸。
 */
internal fun defaultCoverEntry(seed: String?, ratio: CoverRatio): DefaultCoverEntry? {
    val covers = BookCoverShared.currentDefaultCovers(
        PreferenceProviders.get(),
        AppConfigProviders.get().isNightTheme,
    )
    val index = BookCoverShared.pickDefaultCoverIndex(covers.size, seed)
    if (index < 0) return null
    return covers[index]
}

/**
 * 用户自定义默认封面集选图的烘焙路径 ([defaultCoverEntry] 的路径形态, 供只需路径的调用方)。
 *
 * 图集为空时返回 null, 调用方回落内置图。
 */
internal fun defaultCoverFilePath(seed: String?, ratio: CoverRatio): String? {
    val entry = defaultCoverEntry(seed, ratio) ?: return null
    return defaultCoverDisplayPath(entry, ratio)
}
