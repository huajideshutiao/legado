@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.ui.book.read

import io.legado.app.help.topMostViewController
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSData
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSSelectorFromString
import platform.Foundation.create
import platform.UIKit.UIApplication
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIMenuController
import platform.UIKit.UIMenuControllerWillHideMenuNotification
import platform.UIKit.UIMenuItem
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UIResponder
import platform.UIKit.UITapGestureRecognizer
import platform.UIKit.UIView
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewContentMode
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointed

/**
 * iOS 图片长按浮动菜单（平台原生实现 = UIMenuController + responder 链，
 * 复用 [IosTextActionMenu] 同一机制）。
 *
 * 菜单项对照 Android 原版 ReadBookActivity.onImageLongPress 的 popupAction
 * （查看/刷新/保存/选择目录），iOS 无 SAF"选择目录"概念，用"保存到相册"代替
 * （action key: view/refresh/save，由 IosReaderPlatformProvider.onImageAction 分发）。
 */
object IosImageActionMenu {

    private val target = ImageMenuTargetView()

    private val menuItems by lazy {
        listOf(
            UIMenuItem(title = "查看", action = NSSelectorFromString("menuView:")),
            UIMenuItem(title = "刷新", action = NSSelectorFromString("menuRefresh:")),
            UIMenuItem(title = "保存到相册", action = NSSelectorFromString("menuSave:")),
        )
    }

    /** 菜单是否显示中（重复 show 时先收起旧的）。 */
    private var showing = false

    /** 菜单项点击回调（action key: view/refresh/save）。 */
    private var onAction: ((String) -> Unit)? = null

    private var hideObserverInstalled = false

    /**
     * 显示浮动菜单（跟随长按点锚点）。
     *
     * @param anchorX/anchorY 锚点坐标（阅读页内坐标 ≈ keyWindow 坐标，Compose 全屏承载）
     */
    @Suppress("DEPRECATION")
    fun show(anchorX: Float, anchorY: Float, onAction: (String) -> Unit) {
        val window = UIApplication.sharedApplication.keyWindow ?: return
        dismissInternal()
        this.onAction = onAction
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

    private fun dismissInternal() {
        if (!showing && target.superview == null) return
        UIMenuController.sharedMenuController.setMenuVisible(false, animated = false)
        target.removeFromSuperview()
        target.resignFirstResponder()
        showing = false
    }

    /** 监听菜单隐藏（点外部收起）：清回调状态。 */
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
                onAction = null
            }
        }
    }

    /** 菜单项点击：执行动作并收起。 */
    private fun fire(action: String) {
        dismissInternal()
        onAction?.invoke(action)
        onAction = null
    }

    /**
     * 菜单 action 的 responder 目标：隐藏视图 + firstResponder。
     * `@ObjCAction` 方法名与 [menuItems] 的 Selector 一一对应。
     */
    @Suppress("DEPRECATION")
    private class ImageMenuTargetView : UIView(frame = CGRectMake(0.0, 0.0, 1.0, 1.0)) {

        override fun canBecomeFirstResponder(): Boolean = true

        override fun canPerformAction(action: CPointer<out CPointed>?, withSender: Any?): Boolean =
            action == NSSelectorFromString("menuView:") ||
                action == NSSelectorFromString("menuRefresh:") ||
                action == NSSelectorFromString("menuSave:")

        // @ObjCAction 方法参数必须是 ObjC 对象类型 (K/N 限制), Any? 不受支持
        @ObjCAction
        fun menuView(sender: NSObject?) = fire("view")

        @ObjCAction
        fun menuRefresh(sender: NSObject?) = fire("refresh")

        @ObjCAction
        fun menuSave(sender: NSObject?) = fire("save")
    }
}

/**
 * 模态图片预览：黑底 + 等比缩放 [UIImageView]，点按任意位置关闭
 * （对照原版 PhotoDialog；图片已由调用方下载解码，本函数只负责展示）。
 */
internal fun showIosImagePreview(image: UIImage) {
    val vc = ImagePreviewViewController(image)
    topMostViewController()?.presentViewController(vc, animated = true, completion = null)
}

/** 图片预览 VC（Kotlin/Native 子类）：整屏黑底，点击关闭。 */
private class ImagePreviewViewController(image: UIImage) : UIViewController() {

    private val imageView = UIImageView(image = image).apply {
        // K/N 2.3 UIKit 属性绑定为方法形式 (setContentMode/setAutoresizingMask)
        setContentMode(UIViewContentMode.UIViewContentModeScaleAspectFit)
        setAutoresizingMask(UIViewAutoresizingFlexibleWidth or UIViewAutoresizingFlexibleHeight)
    }

    init {
        // 全屏展示（默认 .automatic 在部分上下文会变成 pageSheet 卡片样式，预览图应整屏）
        setModalPresentationStyle(UIModalPresentationFullScreen)
    }

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.setBackgroundColor(UIColor.blackColor)
        imageView.setFrame(view.bounds)
        view.addSubview(imageView)
        // 点按任意位置关闭（对照原版 PhotoDialog 点击关闭）
        view.addGestureRecognizer(
            UITapGestureRecognizer(
                target = this,
                action = NSSelectorFromString("handleTap:")
            )
        )
    }

    @ObjCAction
    fun handleTap(sender: NSObject?) {
        dismissViewControllerAnimated(true, completion = null)
    }
}

/**
 * ByteArray → [UIImage]（与 IosImageOps 内部解码路径一致：NSData.create + UIImage(data:)）。
 * 空数组 / 非图片字节返回 null（UIImage(data:) 解码失败返回 nil）。
 */
internal fun ByteArray.toUIImage(): UIImage? {
    if (isEmpty()) return null
    val nsData = usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
    return UIImage(data = nsData)
}
