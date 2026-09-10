package io.legado.app.ui.root

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * 容器变换的冻结载体: 段开始把页内容录一次, 之后逐帧只把它按目标矩形贴出 (见
 * [containerTransformDraw])。
 *
 * **为何不能逐帧带缩放矩阵重放显示列表**: Skia 字形图集按 (字体, 字号, 矩阵 2×2) 做 key,
 * `DirectMaskSubRun::canReuse` 要求 2×2 完全相等且平移为整数, 容差只有 sk_relax 的 1/1024
 * —— 逐帧不同缩放必然全部 miss, 整块文本 blob 删除重建 + 新 strike + 重光栅入图集。
 * 详情页满屏文字时这一项就能把高刷屏压下来。所以两端都必须"录一次、逐帧只贴一次"。
 *
 * **入口分端是必需的**, 两端拿到这条路的方式不同:
 * - **Android**: 走 HWUI 合成层 (`CompositingStrategy.Offscreen` →
 *   `RenderNode.setUseCompositingLayer`)。离屏表面按节点自身尺寸分配、与变换无关, 绘入时节点
 *   属性一律不施加 (`RenderNodeDrawable::drawContent` 的 `mComposeLayer` 分支), 合成时才 concat
 *   变换并 `drawImageRect` 贴出 —— 字形只光栅一次。
 *   **不可改走位图**: `Canvas(ImageBitmap)` 在本端是软件画布 (ARGB_8888 位图 + framework
 *   Canvas), 而往软件画布 `drawLayer` 会退化成"就地重放录制块"(`AndroidGraphicsLayer.draw` 的
 *   软件分支, V29/V23 实现均不支持软件渲染), 页内持有的 Coil 硬件位图当场抛
 *   `IllegalArgumentException: Software rendering doesn't support hardware bitmaps`
 *   (漫画页 MangaCoilImage 必现)。
 * - **skiko (desktop/iOS/鸿蒙)**: 没有等效开关 —— `RenderNode::onDraw` 的 `saveLayer` 排在
 *   `concat(变换)` **之后**, 离屏按缩放后的设备空间分配, 字形照样每帧重光栅; `renderEffect`
 *   也绕不过去 (纯缩放会被 `decomposeCTM` 并入 layer 矩阵)。只能自己把显示列表光栅成一张位图
 *   再逐帧贴它; 本端光栅目标是 raster SkCanvas, 无 Android 那种硬件位图限制。
 *
 * **内容冻结的取舍**: 只在段开始录一次, 整段不重录 —— 平台行为本来如此 (Android Activity
 * 转场用的就是窗口快照)。代价是段内异步到达的数据 (封面、章节数等) 要等段结束交回实时绘制
 * 才可见。
 */
internal expect class ContainerSnapshot() {
    /**
     * 已录内容对应的页尺寸; [IntSize.Zero] = 尚未录制。
     *
     * 与当前页尺寸不等即作废重录 —— 桌面拖窗改尺寸后旧内容尺寸不对。
     */
    val capturedSize: IntSize

    /**
     * 段开始: 录页内容并转成本端可逐帧贴出的形态。
     *
     * [record] 由调用方提供 (内部即 `layer.record(pageSize) { drawContent() }`), 交由各端在
     * 自己需要的时机调用 —— Android 要先把图层切成离屏合成再录, skiko 要录完再光栅成位图。
     */
    fun capture(
        scope: DrawScope,
        layer: GraphicsLayer,
        pageSize: IntSize,
        record: () -> Unit,
    )

    /**
     * 逐帧: 把已录内容贴到 [dstOffset] + [dstSize]，并按当前插值后的 [cornerRadiusPx]
     * 裁剪；0 表示目标全屏直角。裁剪属于快照合成协议，不能只靠 [alpha] 遮蔽边界。
     */
    fun draw(
        scope: DrawScope,
        layer: GraphicsLayer,
        dstOffset: IntOffset,
        dstSize: IntSize,
        alpha: Float,
        cornerRadiusPx: Float,
    )

    /** 段结束: 释放离屏资源并把 [layer] 属性归位 (全屏离屏约 8MB, 不常驻)。 */
    fun release(layer: GraphicsLayer)
}
