package io.legado.app.utils

import io.legado.app.App
import io.legado.app.model.AndroidRealScreenInfoProvider
import io.legado.app.utils.ScreenInfoProviders

/**
 * 安卓端屏幕尺寸等系统信息工具。
 *
 * screenWidthPx / screenHeightPx 原直接读 `appCtx.resources.displayMetrics`,
 * 现下沉为 [ScreenInfoProviders] 跨平台 provider; 本 object 保留原 API 兼容
 * (PdfFile 等调用方继续用 SystemUtils.screenWidthPx), 首次访问经 provider 间接读取并缓存,
 * 行为与原直接读取一致。
 */
object SystemUtils {

    /**
     * 屏幕像素宽度
     */
    val screenWidthPx by lazy {
        ScreenInfoProviders.get().screenWidthPx
    }

    /**
     * 屏幕像素高度
     */
    val screenHeightPx by lazy {
        ScreenInfoProviders.get().screenHeightPx
    }
}

/**
 * 安卓宿主启动早期注册 [ScreenInfoProvider]。
 *
 * 委托 `appCtx.resources.displayMetrics.widthPixels/heightPixels`, 行为与原 SystemUtils
 * 直接读取一致。调用时机: App.onCreate, 在任何 SystemUtils.screenWidthPx 访问之前。
 *
 * 模式参考 registerAndroidPasswordProvider (BackupAES.kt)。
 */
fun registerAndroidScreenInfoProvider() {
    // 真实全屏 (含状态栏/cutout) 而非内容区: 壁纸/启动图烘焙基准与 iOS nativeBounds/
    // 鸿蒙显示物理像素/桌面 Toolkit.screenSize 对齐 (ScreenInfoProvider 容器只注册一次)
    ScreenInfoProviders.register(AndroidRealScreenInfoProvider)
}
