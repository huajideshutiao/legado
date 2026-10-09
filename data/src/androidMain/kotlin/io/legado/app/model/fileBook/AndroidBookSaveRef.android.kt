package io.legado.app.model.fileBook

import android.net.Uri

/**
 * Android actual: SAF 的 `content://` 引用原样保留, 其余取 `Uri.path`
 * (对照 app 端 `RemoteBookWebDav.downloadRemoteBook` 的角色:
 * `if (uri.isContentScheme()) uri.toString() else uri.path!!`)。
 *
 * scheme 判定不用 app 模块的 `Uri.isContentScheme()` 扩展 —— 该扩展在 :app 源集,
 * :data 的 androidMain 看不到; 这里直接按 scheme 名判断, 语义相同。
 */
actual fun androidSaveRef(savedRef: String): String {
    val uri = Uri.parse(savedRef)
    return if (uri.scheme == "content") uri.toString() else uri.path ?: savedRef
}
