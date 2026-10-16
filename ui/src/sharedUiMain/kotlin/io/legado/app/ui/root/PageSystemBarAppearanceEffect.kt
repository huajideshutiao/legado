package io.legado.app.ui.root

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.Lifecycle

/**
 * 页面系统栏外观的统一下发点 (见 [PageSystemBarAppearance])。
 *
 * 原版一页一 Activity, 页面声明自己的状态栏图标明暗 (音频页/详情页的深色工具栏主题、阅读页的
 * 样式配置) 由该 Activity 建窗口时落到自己的窗口上; 单窗口后必须补回"哪一页的声明当前生效"这
 * 件事实, 本函数就是它:
 *
 * - 只在**本页可见期** (RESUMED; 状态映射见 [RouteLifecycleStore]) 持有声明 —— 被压栈的页
 *   仍留在组合里, 不设这道门, 它会用旧声明盖住栈顶页;
 * - 可见期结束 (被压栈/退后台) 或离开组合 (出栈/转场收尾) 时交还占用, 窗口回到本端非页面
 *   声明的规则;
 * - 转场期间新旧两页同时在组合里, 两副作用的执行顺序由组合时机决定, 页面自己判不出"此刻该
 *   不该交还", 故交还只在仍是占用者时生效 ([PageSystemBarAppearances]);
 * - 声明变化 (背景图加载完成/阅读菜单显隐/样式配置变更) 时重发, 与上次同值不重发;
 * - [refreshTick] 转新时**强制重发**同值: 它是宿主刚把窗口系统栏外观整个重设过的信号
 *   (旋转屏/多窗口/读到新配置), 此时不重发就会丢掉页面声明。
 *
 * 可见性不放进 Compose 状态也不靠重组读: 该翻转可能发生在界面不再重绘时 (桌面窗口隐藏后不再
 * 出帧), 等不到下一次组合, 故由生命周期观察者回调就地处理。进页那一次是就地下发 (首帧即到位),
 * 其余变化走 [LaunchedEffect]; 两处共用 [PageSystemBarEffectRuntime] 的同一份下发判据。
 *
 * @param appearance 本页的声明
 * @param refreshTick 页面外的失效信号: 变化时强制重发当前声明
 */
@Composable
fun PageSystemBarAppearanceEffect(
    appearance: () -> PageSystemBarAppearance,
    refreshTick: Int = 0,
) {
    // 本页本次组合的占用身份 (各页一份): 只作占用者比对用, 内容无意义
    val caller = remember { Any() }
    val window = remember { PlatformServiceProviders.get().window }
    val runtime = remember { PageSystemBarEffectRuntime() }
    OnRouteLifecycle(
        minState = Lifecycle.State.RESUMED,
        onEnter = {
            runtime.visible = true
            runtime.apply(caller, window, appearance(), refreshTick)
        },
        onLeave = {
            runtime.visible = false
            runtime.release(caller, window)
        },
    )
    val declared = appearance()
    LaunchedEffect(declared, refreshTick) {
        runtime.apply(caller, window, declared, refreshTick)
    }
    // 组合销毁 (出栈/转场收尾) 时交还: 可见期结束已交还过则这一步不动作
    DisposableEffect(caller) {
        onDispose { runtime.release(caller, window) }
    }
}

/** [PageSystemBarAppearanceEffect] 的下发判据 (可见资格 + 已下发值 + 刷新信号基线)。 */
private class PageSystemBarEffectRuntime {

    var visible = false

    /** 最后一次已下发的声明; 与待发值相同则不重复下发。 */
    private var sent: PageSystemBarAppearance? = null

    /** 最后一次见过的刷新信号; 与上次不同即说明宿主重设过系统栏外观, 须强制重发。 */
    private var lastRefreshTick: Int? = null

    /** 下发声明:[refreshTick] 转新时强制下发, 否则仅在声明变化时下发。 */
    fun apply(
        caller: Any,
        window: WindowController,
        declared: PageSystemBarAppearance,
        refreshTick: Int,
    ) {
        if (!visible) return
        val forced = lastRefreshTick != refreshTick
        lastRefreshTick = refreshTick
        if (!forced && declared == sent) return
        sent = declared
        PageSystemBarAppearances.claim(caller)
        window.setPageSystemBarAppearance(declared)
    }

    /** 交还占用; 占用已被后继页接管时落空 (落空的旧页不得擦掉新页的声明)。 */
    fun release(caller: Any, window: WindowController) {
        sent = null
        lastRefreshTick = null
        if (PageSystemBarAppearances.release(caller)) {
            window.setPageSystemBarAppearance(null)
        }
    }
}
