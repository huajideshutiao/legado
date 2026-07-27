package io.legado.app.lib.webdav

import android.net.Uri
import io.legado.app.constant.AppLog
import io.legado.app.utils.inputStream
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import splitties.init.appCtx

/**
 * WebDav Android 端扩展: 上传 [Uri] (ContentScheme 或 File scheme)。
 *
 * 主体 WebDav 类已下沉 shared jvmAndAndroidMain (无 Android 依赖),
 * 此处保留 [Uri] 重载, 复用 [WebDav.upload] (ByteArray) 重载以避免破坏
 * WebDav 内部封装 (webDavClient/httpUrl/checkResult 维持原可见性)。
 *
 * 行为差异: 原实现通过 [io.legado.app.utils.toRequestBody] 流式写入,
 * 此处先读入 ByteArray 再委托上传。对现有调用方 (backup 导出/书籍同步)
 * 文件大小可接受。
 */
@Throws(WebDavException::class)
suspend fun WebDav.upload(uri: Uri, contentType: String = "application/octet-stream") {
    kotlin.runCatching {
        withContext(IO) {
            val byteArray = uri.inputStream(appCtx).getOrThrow().use { it.readBytes() }
            upload(byteArray, contentType)
        }
    }.onFailure {
        currentCoroutineContext().ensureActive()
        AppLog.put("WebDav上传失败\n${it.localizedMessage}", it)
        throw WebDavException("WebDav上传失败\n${it.localizedMessage}")
    }
}

