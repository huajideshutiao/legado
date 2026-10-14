package io.legado.app.ui.debug

import kotlin.concurrent.Volatile

/**
 * 弹窗预览入口的宿主注入点。
 *
 * 预览页本身在共享层 (四端同源), 但"进入/退出预览"这一步各端形态不同: 桌面端要先确认、把真实数据
 * 一致性快照到独立数据根、再以该数据根重启进程 (进程内数据根只解析一次, 不重启无法切换),
 * 其余端暂不提供入口。故共享层只暴露可空的进/退回调: 未注册时「我的」页不渲染该条目。
 *
 * 两个回调的判定条件不同 (分别可空): 进入入口只在开发运行期提供; 退出入口只要本次启动落在预览
 * 数据根上就必须提供 —— 进入预览靠重启进程, 重启命令只带启动参数、不带 JVM 系统属性, 新进程里
 * 调试运行期标记恒为假, 若把退出也绑在它上面, 返回箭头就没注册 (用户表现为回不去, 只能关窗口)。
 */
object DialogPreviewLaunchers {

    @Volatile
    private var enter: (() -> Unit)? = null

    @Volatile
    private var exit: (() -> Unit)? = null

    /** 宿主启动时注册: [enter] 为该端「我的」页入口 (null = 不提供), [exit] 为预览页退出回调
     *  (null = 本次启动不在预览数据根上)。 */
    fun register(enter: (() -> Unit)?, exit: (() -> Unit)?) {
        this.enter = enter
        this.exit = exit
    }

    /** 「我的」页取入口: null = 该端不提供, 条目隐藏。 */
    fun getOrNull(): (() -> Unit)? = enter

    /** 预览页退出入口: 预览进程的数据根无法就地切换, 由宿主重启回正式数据根。 */
    fun exitOrNull(): (() -> Unit)? = exit
}
