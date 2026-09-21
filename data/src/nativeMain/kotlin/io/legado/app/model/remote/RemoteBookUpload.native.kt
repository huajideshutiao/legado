package io.legado.app.model.remote

import io.legado.app.lib.webdav.WebDav

/** iOS/鸿蒙本地书均为普通文件路径, 直接按路径上传。 */
internal actual suspend fun WebDav.uploadLocalBook(bookUrl: String) {
    upload(bookUrl.removePrefix("file://"))
}
