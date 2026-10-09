package io.legado.app.model.fileBook

/**
 * 本地书"保存引用" → 写入 `Book.bookUrl` 的取值。
 *
 * 对照 app 端 `RemoteBookWebDav.downloadRemoteBook` 的
 * `if (uri.isContentScheme()) uri.toString() else uri.path!!`:
 * SAF 的 `content://` 引用不是文件系统路径, 必须原样保留; 其余取文件路径。
 *
 * Android actual 按 scheme 判定 (content 原样保留, 其余取 `Uri.path`);
 * 桌面 / iOS / 鸿蒙的保存引用只会是文件路径或 `file://` 引用, actual 原样返回。
 */
expect fun androidSaveRef(savedRef: String): String
