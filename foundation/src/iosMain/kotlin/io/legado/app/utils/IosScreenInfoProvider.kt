@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.legado.app.utils

import kotlinx.cinterop.useContents
import platform.UIKit.UIScreen

/**
 * [ScreenInfoProvider] iOS 实现: 用 [UIScreen.nativeBounds] 读主屏物理像素尺寸,
 * 替代 app 端 `appCtx.resources.displayMetrics.widthPixels/heightPixels`。
 *
 * nativeBounds 恒为竖屏基准且已含 scale (非 points), 与 app 端 displayMetrics 的
 * 物理像素语义一致; 对照 desktop 端 `Toolkit.getDefaultToolkit().screenSize`。
 */
private val iosScreenInfoProvider = object : ScreenInfoProvider {
    override val screenWidthPx: Int
        get() = UIScreen.mainScreen.nativeBounds.useContents { size.width.toInt() }
    override val screenHeightPx: Int
        get() = UIScreen.mainScreen.nativeBounds.useContents { size.height.toInt() }
}

/** 宿主启动早期注册一次 (任何对话框尺寸计算之前)。 */
fun registerIosScreenInfoProvider() {
    ScreenInfoProviders.register(iosScreenInfoProvider)
}
