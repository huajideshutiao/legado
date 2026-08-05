@file:OptIn(ExperimentalForeignApi::class)

package io.legado.app.ui.book.read

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIApplication
import platform.UIKit.UIMenuController
import platform.UIKit.UIMenuControllerWillHideMenuNotification
import platform.UIKit.UIMenuItem
import platform.UIKit.UIResponder
import platform.UIKit.UIView
import platform.darwin.NSObject
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointed

/**
 * iOS 文本操作浮动菜单（平台原生实现 = UIMenuController + responder 链，
 * 对标 Android 原版 TextActionMenu 的 ActionMode.TYPE_FLOATING 语义）。
 *
 * # 实现
 * - [UIMenuController] 是 iOS 系统文本选择菜单的引擎（UITextView 内部同款）；
 *   菜单项集合与 Android 原版 content_select_action.xml 一致（替换/复制/书签/朗读/
 *   查词/全文搜索/浏览器/分享）
 * - 点击分发走 responder 链：显示时挂一个隐藏 [UIView] 子类并成为 firstResponder，
 *   菜单项 action 经 `canPerformAction` 命中后调用 [@ObjCAction] 方法（社区标准做法）
 * - 菜单隐藏（点外部/动作完成）→ [onMenuFinally]（对标原版
 *   onDestroyActionMode → onMenuActionFinally → cancelSelect）
 */
object IosTextActionMenu {

    private val target = MenuTargetView()

    private val menuItems by lazy {
        listOf(
            // K/N 无 kotlinx.cinterop.Selector (2.x 移除), 用 NSSelectorFromString 构造
            UIMenuItem(title = "替换", action = NSSelectorFromString("menuReplace:")),
            UIMenuItem(title = "复制", action = NSSelectorFromString("menuCopy:")),
            UIMenuItem(title = "书签", action = NSSelectorFromString("menuBookmark:")),
            UIMenuItem(title = "朗读", action = NSSelectorFromString("menuAloud:")),
            UIMenuItem(title = "查词", action = NSSelectorFromString("menuDict:")),
            UIMenuItem(title = "全文搜索", action = NSSelectorFromString("menuSearchContent:")),
            UIMenuItem(title = "浏览器", action = NSSelectorFromString("menuBrowser:")),
            UIMenuItem(title = "分享", action = NSSelectorFromString("menuShare:")),
        )
    }

    /** 菜单是否显示中（重复 show 时先收起旧的）。 */
    private var showing = false

    /** 菜单项点击回调（action key 与 Android 原版菜单项一一对应）。 */
    private var onAction: ((String) -> Unit)? = null

    /** 菜单关闭回调（动作完成或点外部收起；对标原版 onMenuActionFinally）。 */
    private var onMenuFinally: (() -> Unit)? = null

    private var hideObserverInstalled = false

    /**
     * 显示浮动菜单（跟随选区锚点）。
     *
     * @param anchorX/anchorY 锚点坐标（阅读页内坐标 ≈ keyWindow 坐标，Compose 全屏承载）
     */
    @Suppress("DEPRECATION")
    fun show(
        anchorX: Float,
        anchorY: Float,
        onAction: (String) -> Unit,
        onMenuFinally: () -> Unit,
    ) {
        val window = UIApplication.sharedApplication.keyWindow ?: return
        dismissInternal()
        this.onAction = onAction
        this.onMenuFinally = onMenuFinally
        // 挂隐藏 responder 视图并成为 firstResponder：菜单项 action 沿 responder 链命中本视图
        window.addSubview(target)
        target.becomeFirstResponder()
        UIMenuController.sharedMenuController.setMenuItems(menuItems)
        // showMenuFromRect 在 K/N 2.3 绑定不可用, 用两步式 setTargetRect + setMenuVisible
        UIMenuController.sharedMenuController.setTargetRect(
            CGRectMake(anchorX.toDouble(), anchorY.toDouble(), 40.0, 40.0),
            inView = target,
        )
        UIMenuController.sharedMenuController.setMenuVisible(true, animated = true)
        showing = true
        installHideObserver()
    }

    /** 立即收起菜单（动作完成后调用；对标原版 TextActionMenu.dismiss）。 */
    fun dismiss() {
        onMenuFinally = null
        dismissInternal()
    }

    private fun dismissInternal() {
        if (!showing && target.superview == null) return
        UIMenuController.sharedMenuController.setMenuVisible(false, animated = false)
        target.removeFromSuperview()
        target.resignFirstResponder()
        showing = false
    }

    /** 监听菜单隐藏（点外部收起）：对标原版 onDestroyActionMode 非 dismissByApp → onMenuActionFinally。 */
    private fun installHideObserver() {
        if (hideObserverInstalled) return
        hideObserverInstalled = true
        NSNotificationCenter.defaultCenter.addObserverForName(
            name = UIMenuControllerWillHideMenuNotification,
            `object` = null,
            queue = null,
        ) { _ ->
            if (showing) {
                showing = false
                target.removeFromSuperview()
                target.resignFirstResponder()
                onMenuFinally?.invoke()
                onMenuFinally = null
            }
        }
    }

    /** 菜单项点击：执行动作 → 收起并触发 finally（对标原版 onActionItemClicked → onMenuActionFinally）。 */
    private fun fire(action: String) {
        dismissInternal()
        onAction?.invoke(action)
        onMenuFinally?.invoke()
        onMenuFinally = null
    }

    /**
     * 菜单 action 的 responder 目标：隐藏视图 + firstResponder。
     * `@ObjCAction` 方法名与 [menuItems] 的 Selector 一一对应。
     */
    @Suppress("DEPRECATION")
    private class MenuTargetView : UIView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0)) {

        override fun canBecomeFirstResponder(): Boolean = true

        override fun canPerformAction(action: CPointer<out CPointed>?, withSender: Any?): Boolean =
            action == NSSelectorFromString("menuReplace:") ||
                action == NSSelectorFromString("menuCopy:") ||
                action == NSSelectorFromString("menuBookmark:") ||
                action == NSSelectorFromString("menuAloud:") ||
                action == NSSelectorFromString("menuDict:") ||
                action == NSSelectorFromString("menuSearchContent:") ||
                action == NSSelectorFromString("menuBrowser:") ||
                action == NSSelectorFromString("menuShare:")

        // @ObjCAction 方法参数必须是 ObjC 对象类型 (K/N 限制), Any? 不受支持
        @ObjCAction
        fun menuReplace(sender: NSObject?) = fire("replace")

        @ObjCAction
        fun menuCopy(sender: NSObject?) = fire("copy")

        @ObjCAction
        fun menuBookmark(sender: NSObject?) = fire("bookmark")

        @ObjCAction
        fun menuAloud(sender: NSObject?) = fire("aloud")

        @ObjCAction
        fun menuDict(sender: NSObject?) = fire("dict")

        @ObjCAction
        fun menuSearchContent(sender: NSObject?) = fire("search_content")

        @ObjCAction
        fun menuBrowser(sender: NSObject?) = fire("browser")

        @ObjCAction
        fun menuShare(sender: NSObject?) = fire("share")
    }
}
