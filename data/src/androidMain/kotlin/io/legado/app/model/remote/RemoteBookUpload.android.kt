package io.legado.app.model.remote

import android.net.Uri
import io.legado.app.constant.AppLog
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.ui.platform.sharedAppContext
import io.legado.app.utils.isContentScheme
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * 对照原 app 端 `RemoteBookWebDav.upload` + `WebDav.upload(uri)` (WebDavAndroidExt):
 * content scheme 读流成字节后上传, 否则取 `Uri.path` 走本地路径上传。
 */
internal actual suspend fun WebDav.uploadLocalBook(bookUrl: String) {
    if (!bookUrl.isContentScheme()) {
        upload(Uri.parse(bookUrl).path!!)
        return
    }
    runCatching {
        withContext(IO) {
            val resolver = sharedAppContext?.contentResolver
                ?: throw WebDavException("WebDav上传失败\nApplicationContext 未注册")
            val byteArray = resolver.openInputStream(Uri.parse(bookUrl))
                ?.use { it.readBytes() }
                ?: throw WebDavException("WebDav上传失败\n未获取到文件")
            upload(byteArray)
        }
    }.onFailure {
        currentCoroutineContext().ensureActive()
        AppLog.put("WebDav上传失败\n${it.localizedMessage}", it)
        throw WebDavException("WebDav上传失败\n${it.localizedMessage}")
    }
}
