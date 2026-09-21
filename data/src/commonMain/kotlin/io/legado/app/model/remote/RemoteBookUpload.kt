package io.legado.app.model.remote

import io.legado.app.lib.webdav.WebDav

/**
 * 上传本地书文件到 WebDav (对照 app 端原 `RemoteBookWebDav.upload` 的 scheme 分支)。
 *
 * Android actual: content scheme 走 ContentResolver 读流后按字节上传 (等价原
 * `WebDav.upload(uri)`), 其余按 `Uri.path` 走本地路径上传; 其他端只有普通路径。
 */
internal expect suspend fun WebDav.uploadLocalBook(bookUrl: String)
