@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.ui.book.read

import io.legado.app.help.topMostViewController
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSSelectorFromString
import platform.Foundation.create
import platform.UIKit.UIColor
import platform.UIKit.UIImage
import platform.UIKit.UIImageView
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UITapGestureRecognizer
import platform.UIKit.UIViewAutoresizingFlexibleHeight
import platform.UIKit.UIViewAutoresizingFlexibleWidth
import platform.UIKit.UIViewContentMode
import platform.UIKit.UIViewController
import platform.darwin.NSObject

/**
 * 模态图片预览：黑底 + 等比缩放 [UIImageView]，点按任意位置关闭
 * （对照原版 PhotoDialog；图片已由调用方下载解码，本函数只负责展示）。
 */
internal fun showIosImagePreview(image: UIImage) {
    val vc = ImagePreviewViewController(image)
    topMostViewController()?.presentViewController(vc, animated = true, completion = null)
}

/** 图片预览 VC（Kotlin/Native 子类）：整屏黑底，点击关闭。 */
// K/N 2.3: ObjC 子类 super 必须调用指定构造器 (initWithNibName:bundle:), UIViewController() 便利构造器会报
// "Unable to call non-designated initializer as super constructor"
private class ImagePreviewViewController(image: UIImage) :
    UIViewController(nibName = null, bundle = null) {

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

    @OptIn(kotlinx.cinterop.BetaInteropApi::class)
    @ObjCAction
    fun handleTap(sender: NSObject?) {
        dismissViewControllerAnimated(true, completion = null)
    }
}

/**
 * ByteArray → [UIImage]（与 IosImageOps 内部解码路径一致：NSData.create + UIImage.imageWithData）。
 * 空数组 / 非图片字节返回 null（+imageWithData: 解码失败返回 nil，K/N 映射为 UIImage?）。
 */
@OptIn(kotlinx.cinterop.BetaInteropApi::class)
internal fun ByteArray.toUIImage(): UIImage? {
    if (isEmpty()) return null
    val nsData = usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
    return UIImage.imageWithData(nsData)
}
