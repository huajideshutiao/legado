package io.legado.app.help

/**
 * RSS 阅读窗口工具栏动作 (桌面端 RSS 直开浏览器窗口时注入, 2026-08-07)。
 *
 * 用户拍板: RSS 阅读页在桌面端去掉页面外壳, 收藏/朗读/分享/登录等操作全部移入
 * 浏览器窗口工具栏。窗口按钮动作经此类回调回 shared (书架操作/TTS/分享/登录),
 * 窗口工具条的星收藏态由 [onStarChanged] 反推更新 (平台实现挂接到工具栏)。
 *
 * 生命周期: 路由出栈时 shared 侧调用 [onDetach] (平台实现关闭窗口), 窗口被用户
 * 关闭时平台实现回调路由出栈 (经 openRssReader 传入的 onClosed)。两者幂等。
 */
class RssToolbarActions(
    /** 初始收藏态 (星图标实心/空心)。 */
    var starred: Boolean,
    /** 收藏/取消收藏: shared 侧执行书架操作, 完成后更新 [starred] 并通知 [onStarChanged]。 */
    val onStarToggle: () -> Unit,
    /** 朗读: 平台窗口抓取当前页 outerHTML 后交回 (null = 抓取失败, shared 侧自行忽略)。 */
    val onReadAloud: (suspend () -> String?) -> Unit,
    /** 分享当前文章地址。 */
    val onShare: () -> Unit,
    /** 书源登录 (URL 登录时直接开登录窗口)。 */
    val onLogin: () -> Unit,
) {
    /** 收藏态变化通知: 平台实现挂接 → 更新窗口工具栏星图标。 */
    var onStarChanged: ((Boolean) -> Unit)? = null

    /** 路由出栈通知: shared 在 RSS 路由 dispose 时调用 → 平台关闭窗口。 */
    var onDetach: (() -> Unit)? = null
}
