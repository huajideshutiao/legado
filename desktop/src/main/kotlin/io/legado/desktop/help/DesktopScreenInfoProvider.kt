package io.legado.desktop.help

import io.legado.app.utils.ScreenInfoProvider
import io.legado.app.utils.ScreenInfoProviders
import java.awt.Toolkit

/**
 * [ScreenInfoProvider] 桌面 JVM 实现。
 *
 * 用 `Toolkit.getDefaultToolkit().screenSize` 读取主屏幕像素尺寸, 替代 app 端
 * `appCtx.resources.displayMetrics.widthPixels/heightPixels`。
 *
 * # 与 app 端差异
 * - **多显示器**: Toolkit.getScreenSize 只返回主屏幕尺寸 (与 app 端 single-display 语义对齐),
 *   不区分窗口当前所在显示器 (GraphicsEnvironment 可拿全屏集合, 此处不需要)。
 * - **DPI 缩放**: 返回物理像素 (与 app 端 displayMetrics.widthPixels 一致)。
 *
 * 注册: desktop `Main.kt` 中 `registerDesktopScreenInfoProvider()`。
 * 模式参考 registerDesktopPasswordProvider。
 */
private val desktopScreenInfoProvider = object : ScreenInfoProvider {
    override val screenWidthPx: Int
        get() = Toolkit.getDefaultToolkit().screenSize.width
    override val screenHeightPx: Int
        get() = Toolkit.getDefaultToolkit().screenSize.height
}

/** 桌面端 main 入口早期注册一次。 */
fun registerDesktopScreenInfoProvider() {
    ScreenInfoProviders.register(desktopScreenInfoProvider)
}
