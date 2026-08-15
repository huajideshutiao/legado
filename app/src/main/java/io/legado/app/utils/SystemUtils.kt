package io.legado.app.utils

import io.legado.app.App

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
    ScreenInfoProviders.register(object : ScreenInfoProvider {
        override val screenWidthPx: Int
            get() = App.instance.resources.displayMetrics.widthPixels
        override val screenHeightPx: Int
            get() = App.instance.resources.displayMetrics.heightPixels
    })
}
