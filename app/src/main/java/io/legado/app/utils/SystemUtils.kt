package io.legado.app.utils

import splitties.init.appCtx

object SystemUtils {

    /**
     * 屏幕像素宽度
     */
    val screenWidthPx by lazy {
        appCtx.resources.displayMetrics.widthPixels
    }

    /**
     * 屏幕像素高度
     */
    val screenHeightPx by lazy {
        appCtx.resources.displayMetrics.heightPixels
    }
}
