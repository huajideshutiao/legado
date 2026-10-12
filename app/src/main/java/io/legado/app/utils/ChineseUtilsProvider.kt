package io.legado.app.utils

import io.legado.app.help.coroutine.Coroutine

/**
 * 简繁词典缓存定位器的安卓注册 (转换主体已下沉 shared/jvmAndAndroidMain 的 [ChineseUtils])。
 *
 * 宿主启动早期注册一次 (App.onCreate 时机, 经 registerAndroidJsEngines 同位置调起)。
 * 缓存不可用即后台拉取 —— 原 loadDict else 分支的下载副作用内置于此 lambda,
 * 保持 RemoteAssetsUtils/Coroutine 全留 app, shared 侧仅 getPath 定位。
 *
 * 触发判据与加载方是同一条 ([isCachedTcDictUsable]), 并原样传给下载入口 (下载侧不自带判据):
 * 两侧判据不一致时, 加载方判不可用而下载方当有效, 缓存就永远不会重下。
 *
 * 桌面端等价实现见 `registerDesktopJsEngines` 的 DesktopTcDictCachePathProvider。
 */
fun registerAndroidChineseUtils() {
    ChineseUtils.pathProvider = TcDictCachePathProvider { fileName ->
        RemoteAssetsUtils.getTcCachePath(fileName).also { file ->
            if (!isCachedTcDictUsable(file)) {
                Coroutine.async {
                    RemoteAssetsUtils.downloadTcIfNeeded(fileName, ::isCachedTcDictUsable)
                }
            }
        }
    }
}
