package io.legado.app.ui.root

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 页面转场的一段: 起点栈 [from]、终点栈 [to]、是否沿本段轨迹倒放 [reversing]。
 *
 * 段描述是**纯值**: 由页面栈与当前段当场算出, 不保存任何可变动画标志 —— "是否在转场中"只能由
 * 段两端与进度当场算出; 任何独立存储的标志都可能停在与动画事实矛盾的值上 (动画在"终值已到、
 * 完成回调未调度"的窗口被查询时标志无法自行复位), 消费方按错误的态渲染会整屏空白。
 *
 * **本段任意进度下至少有一页可见**, 这是无空白的结构性保证:
 * - 前进段 ([forward]): 让位页 ([slidingIds]) 恒为 OldPage —— 该角色 alpha 恒 1, 进度 0 时位移
 *   也是 0 (居中), 故它总在屏幕上; 新页在起点位虽在屏幕右侧外, 但不是唯一在场页;
 * - 返回段: 落位页是 TargetPage, 该角色 alpha 恒 1, 进度 0 时位于 -25% 处;
 * - 倒放段: 与正放共用同一份角色与可见性保证 —— 只是进度沿本段轨迹退回 0f。
 * 整屏空白的唯一途径是把**唯一**在场页算成 NewPage 并配上起点位进度 —— 本段里 NewPage 只可能
 * 出现在"前进段的终点栈顶", 而前进段恒有让位页在场, 该组合不可达。
 *
 * 角色判定只看段的轨迹 (落位页 = [to] 的栈顶, 让位页 = [slidingIds]), 与播放方向 [reversing]
 * 无关 —— 倒放不改角色。若按"当前页面栈"或"被推入页/起点栈顶"重算, 出栈页会从 OutgoingPage
 * 翻成 OldPage、落位页被判 null, 倒放首帧位移从 +slide·p 跳到 -shift·p, 肉眼即"页面跳一下"
 * (pop/replace/单段前进倒放必触发; 普通单页 push 倒放因两分支恰好等价而掩盖)。
 */
data class RouteTransitionSegment(
    val from: List<RouteEntry>,
    val to: List<RouteEntry>,
    val reversing: Boolean,
) {
    /** 段方向: 终点栈不比起点栈浅即视为前进 (含等深的 replace/单段前进)。 */
    val forward: Boolean get() = to.size >= from.size

    /** 本段被推入的页面 (倒放时就是要退回屏幕右侧的那几页)。 */
    val retreating: List<RouteEntry> get() = to.filterNot { t -> from.any { it.id == t.id } }

    /** 本段被弹出的页面 (pop 单个 / popTo 多个 / replace 被换掉的那页)。 */
    val dropped: List<RouteEntry> get() = from.filterNot { f -> to.any { it.id == f.id } }

    /**
     * 单段前进: 有弹出页且方向向前 (pop 紧接 push, 如详情→目录→选章节→阅读)。
     * 滑出的是出栈页 (目录) 而非栈内倒数第二页, 中间页全程不露脸。
     */
    val overOutgoing: Boolean get() = dropped.isNotEmpty() && forward

    /**
     * 本段参与渲染的页面 (含正在离场的): 离场页必须留在组合里播完动画,
     * 否则返回时会先空一格。单段前进时出栈页插在栈顶之下 —— 新页在上才是前进的 z 序,
     * 排到栈尾会让不透明的旧页盖住新页滑入 (中段两层 alpha 之和 <1 会透出根背景)。
     */
    val displayEntries: List<RouteEntry>
        get() = when {
            reversing -> from + retreating
            overOutgoing -> to.dropLast(1) + dropped + to.takeLast(1)
            !forward -> to + dropped
            else -> to
        }

    /** 正常段的让位页: 纯前进 = 终点栈的倒数第二页; 其余 = 本段弹出的页面。 */
    private val slidingIds: Set<RouteEntryId>
        get() = if (forward && !overOutgoing) {
            to.getOrNull(to.lastIndex - 1)?.let { setOf(it.id) } ?: emptySet()
        } else {
            dropped.mapTo(mutableSetOf()) { it.id }
        }

    /**
     * 本页在本段的角色; null = 不参与本段 (移出屏幕, 不绘制也不参与命中测试)。
     *
     * 角色是段轨迹的属性, 与播放方向无关: 倒放只是进度沿同一段轨迹倒走, 正放/倒放必须共用
     * 同一份角色, 倒放首帧才与正放当前帧位移严格连续 (反例与后果见类 KDoc)。
     */
    fun roleOf(entry: RouteEntry): TransitionRole? {
        val isTarget = entry.id == to.lastOrNull()?.id
        val isSliding = entry.id in slidingIds
        return when {
            isTarget -> if (forward) TransitionRole.NewPage else TransitionRole.TargetPage
            isSliding -> if (forward) TransitionRole.OldPage else TransitionRole.OutgoingPage
            else -> null
        }
    }
}

/**
 * 页面转场状态机: 唯一可变状态 = **当前段** [segment] 与 **进度** [progress]。
 *
 * "是否处于转场中"由 [resolve] 当场算出 (见 [animating]), 不存在可独立于动画事实被写错的
 * 标志; 段播完时 [settle] 把段收敛成同一份栈并把进度归位到 1f, 两次写入之间无挂起点, 在同一次
 * 快照中对观察者原子生效。进度的逐帧读取只允许发生在 graphicsLayer 块内 (图层阶段读), 见
 * [rendersLiveProgress]。
 *
 * 进度方向与视觉含义 (Android 平台 spec):
 * - 1f = 稳定态: 栈内页面都在终位;
 * - 0f = 本段起点位: 前进段 = 新页整体在屏幕右侧外 (位移 = 屏宽), 返回段 = 目标页在 -25% 处;
 * - 倒放 = 保持本段起止栈不变, 进度从当前位置退回 0f, 再 [settle] 归位。
 *
 * @param initialEntries 初始页面栈 (启动时为单页主界面)
 */
@Stable
class RouteTransitionState(initialEntries: List<RouteEntry>) {

    /** 已应用到动画的段: 静止态时两端相等。 */
    var segment: RouteTransitionSegment by mutableStateOf(
        RouteTransitionSegment(initialEntries, initialEntries, reversing = false)
    )
        private set

    /** 进度: 0f = 本段起点位, 1f = 终位。isRunning 由官方 Animatable 维护。 */
    val progress: Animatable<Float, *> = Animatable(1f)

    /** 本段尚未落定 (两端不同)。 */
    private fun unsettled(seg: RouteTransitionSegment): Boolean = seg.from != seg.to

    /**
     * 算出本次导航该播的段; null = 已静止 (页面栈就是本段终点且两端相等), 不需要动画。
     *
     * **纯函数, 不改任何状态**, 供组合期直接调用 —— 首帧即按本段起点位渲染, 不必等 effect
     * 的 snapTo 到位。
     *
     * 三种情形:
     * - 页面栈 == 本段终点 → 沿用本段 (可能是续播, 也可能是刚开播);
     * - 页面栈退回本段起点且本段未落定 → 沿本段轨迹倒放 (含"进场动画中途按返回"与
     *   "退场动画中途重新前进"两个方向);
     * - 其余 (含动画中途改道) → 开新段: 起点 = 上一段的终点栈, 终点 = 新的页面栈。新段从其
     *   起点位起播, 打断瞬间页面先落到上一段终点位再播新段。
     */
    fun resolve(entries: List<RouteEntry>): RouteTransitionSegment? {
        if (entries == segment.to) {
            return if (unsettled(segment)) segment.copy(reversing = false) else null
        }
        if (entries == segment.from && unsettled(segment)) {
            return segment.copy(reversing = true)
        }
        return RouteTransitionSegment(from = segment.to, to = entries, reversing = false)
    }

    /**
     * 是否处于转场中 (供状态栏高度冻结与转场圆角消费)。
     *
     * 判据 = 段未落定 ([resolve] 返回非空), 只在导航与落定时变化。进度值不参与判据: 在组合期
     * 读 [progress] 会把整棵页面树订阅到动画帧上逐帧重组; 段落定与进度归位在同一次快照中生效,
     * 不存在"段已落定而进度未到终位"可被观察到的中间态。
     */
    fun animating(entries: List<RouteEntry>): Boolean {
        return resolve(entries) != null
    }

    /**
     * 本段渲染进度是否逐帧取真实 [progress] (false = 固定取起点位 0f)。
     *
     * 与进度取值的对应关系:
     * - 倒放段: 沿本段轨迹往回走, 从**当前**进度继续 (按起点位渲染会让页面跳一下) → 真实值;
     * - 本段已被 effect 应用 ([resolve] 结果即 [segment]): 真实进度 (含"动画刚跑完、settle
     *   尚未执行"那一帧, 此时渲染终位而非起点位);
     * - 新段尚未被 effect 应用: 首帧必须落在起点位, 否则会先闪一帧终态再动画 → 0f。
     */
    fun rendersLiveProgress(entries: List<RouteEntry>): Boolean {
        val seg = resolve(entries) ?: return true
        return seg.reversing || seg == segment
    }

    /** 写入本次要播的段 (调用方随后按 [RouteTransitionSegment.reversing] 驱动进度)。 */
    fun applySegment(next: RouteTransitionSegment) {
        segment = next
    }

    /** 段动画播完后落定: 段收敛成同一份栈、进度归位到 1f。 */
    suspend fun settle(entries: List<RouteEntry>) {
        segment = RouteTransitionSegment(entries, entries, reversing = false)
        progress.snapTo(1f)
    }
}
