package io.legado.app.ui.root

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * 全局"切换主页 tab"请求通道。
 *
 * 背景: 主页 tab 切换 (MainScreen HorizontalPager) 是 MainRoute 组合内私有状态,
 * 外部代码 (如桌面端窗口控制栏菜单的"设置"项——主页在栈顶时切"我的"tab 而非
 * push 设置页) 无法直接访问。这里提供全局单向指令流: 外部 tryEmit 请求,
 * MainRoute 组合内 collect 后经 pageSelections 平滑滚动到目标 tab。
 *
 * 目标 tab 被用户隐藏 (底栏配置) 时 indexOf 返回 -1, MainRoute 侧忽略请求,
 * 不回落不报错 (与 reselect 语义一致)。
 */
object MainTabSwitcher {
    private val requests = MutableSharedFlow<MainTab>(extraBufferCapacity = 8)

    /** 请求切换到 [tab] (非挂起, 缓冲溢出时丢弃, 无副作用)。 */
    fun switchTo(tab: MainTab) {
        requests.tryEmit(tab)
    }

    /** MainRoute 组合内消费的请求流。 */
    val flow: MutableSharedFlow<MainTab> get() = requests
}
