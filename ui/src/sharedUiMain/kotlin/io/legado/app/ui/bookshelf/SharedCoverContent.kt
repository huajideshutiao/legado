package io.legado.app.ui.bookshelf

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
 * 封面槽比例语义, 对照原版 `CoverImageView.coverRatio` (BookCover.CoverRatio.VIDEO/NOVEL 枚举直传)。
 *
 * 原版比例由布局档位硬编码 (列表 isVideoStyle ? VIDEO : NOVEL / 网格 NOVEL / 视频卡 VIDEO);
 * shared 版增加自适应档: 按封面真实宽高在 16:9 与 3:4 间决议 (横向剧照 16:9, 纵向海报 3:4),
 * 用于视频卡片形态 (单列大卡/瀑布流/网格)。行式列表行高钉死、宽度按比例反算, 横竖封面混排
 * 会让同行条目的封面宽度参差, 恒固定比例。
 */
enum class CoverSlotRatio {
    /** 3:4 小说封面 (对照 CoverRatio.NOVEL) */
    NOVEL,
    /** 16:9 视频封面 (对照 CoverRatio.VIDEO) */
    VIDEO,
    /** 按封面真实宽高在 16:9 与 3:4 间自适应 (视频卡片形态) */
    AUTO,
}

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
 * @param coverRatio 封面比例档位 (对照原版 `CoverImageView.coverRatio`)
 * @param reloadTick 封面重载信号 (configTick): 变化时重启加载, 不变不额外触发
 * @param nameAuthorOverlay 默认封面上的竖排书名/作者叠字 (分组封面无书名, 不叠)
 */
@Composable
internal fun SharedCoverContent(
    source: CoverSource,
    modifier: Modifier,
    coverRatio: CoverSlotRatio,
    reloadTick: Int,
    nameAuthorOverlay: Boolean,
) {
    val loader = remember { BookImageLoaders.getOrNull() }
    // 页面级比例布局状态 (无持有者时为 null): AUTO 档已确定的比例从它恢复, 条目被 Lazy
    // 销毁重建或位图缓存淘汰后不重测; 身份与位图分区查询同源
    val ratioState = LocalCoverRatioLayout.current
    val coverResource = CoverResourceKey(source.coverUrl, source.origin, source.persistentCover)
    // useDefaultCover 时跳过网络加载, 直接走默认封面链 (对照原 View 版封面组件 load 行为);
    // 每次组合读 prefs (不 remember): 宿主重组触发 LaunchedEffect 重启时读到的是最新配置
    val useDefaultCover = AppConfigProviders.get().useDefaultCover
    // 首帧即出真图: 同一张封面已在别处 (书架格子/列表/上一次详情) 解码过时, 组合期同步取回,
    // 不再让首帧摆默认封面——共享元素飞的是"可见那一端"的内容, 详情页首帧占位会被放大到整个
    // 飞行尺寸, 观感即"闪一下默认封面"。按真实来源与分区查询, 取回当前可复用的最大那份;
    // 若它仍比当前显示尺寸小, 会先糊一帧, 下面按自己尺寸解完替换。
    // 位图与"是否默认封面"合成一个 state: 一次加载只引发一次重组。
    // 首帧查询在 state 创建时做一次, 结果直接作为同一显示 state 的初值 (不再另存一份初始位图);
    // 资源身份按真实 URL / 来源 / 分区 / 是否参与封面用途换代, 同 URL 换分区也会重新取。
    var coverState by remember(
        source.coverUrl,
        source.cacheKey,
        source.origin,
        source.persistentCover,
        source.shareDecodedCover,
    ) {
        val fromCache = if (!source.shareDecodedCover || useDefaultCover || source.coverUrl.isNullOrBlank()) {
            null
        } else {
            DecodedBitmapCache.peekCover(source.coverUrl, source.origin, source.persistentCover)
        }
        mutableStateOf(fromCache?.let { CoverBitmap(it, false) } ?: NoCoverBitmap)
    }
    // 比例决议: 固定档恒为对应枚举; 自适应档按位图横竖特征动态决议 (width > height 为
    // VIDEO, 反之为 NOVEL)。缓存命中时首帧直接决议真实比例, 避免闪烁; 未命中时以 VIDEO
    // 起步 (对照原视频槽起步语义), 位图就绪时当帧切换
    var resolvedCoverRatio by remember(
        source.coverUrl,
        source.cacheKey,
        source.origin,
        source.persistentCover,
        source.shareDecodedCover,
        coverRatio,
        ratioState,
    ) {
        mutableStateOf(
            when (coverRatio) {
                CoverSlotRatio.NOVEL -> CoverRatio.NOVEL
                CoverSlotRatio.VIDEO -> CoverRatio.VIDEO
                // 页面级已确定的比例优先 (对结果集仍保留的资源始终成立); 其次按当前显示位
                // 已有的真实封面图横竖决议, 默认封面不作依据 (按 VIDEO 起步)
                CoverSlotRatio.AUTO -> ratioState?.resolvedRatio(coverResource)
                    ?: coverState.takeUnless { it.isDefault }?.bitmap
                        ?.let { if (it.width > it.height) CoverRatio.VIDEO else CoverRatio.NOVEL }
                    ?: CoverRatio.VIDEO
            }
        )
    }
    // AUTO 档初始已有真图时在成功组合提交时补种当前已决议的空记录 (不覆盖已有,
    // 不用固定/派生裁剪图的宽高冒充新自然比例); 默认封面与失败不提交
    if (ratioState != null && coverRatio == CoverSlotRatio.AUTO) {
        SideEffect {
            val shown = coverState
            if (!shown.isDefault && shown.bitmap != null) {
                ratioState.seedResolvedRatioIfAbsent(coverResource, resolvedCoverRatio)
            }
        }
    }
    // 尺寸只用于首次按显示大小降采样；后续窗口 resize 不应重新发起封面请求。
    // 否则每跨过一个量化尺寸档都会再次进入图片 Interceptor，重复执行书源 JS header 规则。
    val displaySize = remember { MutableStateFlow(IntSize.Zero) }
    LaunchedEffect(
        source.coverUrl,
        source.cacheKey,
        source.origin,
        source.persistentCover,
        source.shareDecodedCover,
        loader,
        useDefaultCover,
        coverRatio,
        reloadTick,
    ) {
        if (loader == null) return@LaunchedEffect
        val decodeSize = firstValidCoverDecodeSize(displaySize)
        val ratio = resolvedCoverRatio

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
                    // 按真实来源与分区把这张已解出的封面登记到本端现有有界缓存, 供
                    // 书架↔详情↔列表同步取回; 是否留得住由库自身预算与策略决定
                    DecodedBitmapCache.recordCover(coverUrl, source.origin, source.persistentCover, bmp)
                }
                if (coverRatio == CoverSlotRatio.AUTO) {
                    // 登记的是 AUTO 已实际决议的布局比例; 固定档裁剪产物无 AUTO 决议,
                    // 不登记不覆盖 (固定档显示仍恒常量比例), 默认封面与失败不写
                    val resolved = if (bmp.width > bmp.height) CoverRatio.VIDEO else CoverRatio.NOVEL
                    resolvedCoverRatio = resolved
                    ratioState?.recordResolvedRatio(coverResource, resolved)
                }
                coverState = CoverBitmap(bmp, false)
            }
        }
    }
    val outerModifier = modifier.coverSizeModifier(resolvedCoverRatio)
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
private fun Modifier.coverSizeModifier(ratio: CoverRatio): Modifier =
    aspectRatio(
        ratio = ratio.widthRatio.toFloat() / ratio.heightRatio,
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
