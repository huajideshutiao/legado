package io.legado.app.help

import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.storage.NativeZipCodec
import io.legado.app.model.analyzeRule.AnalyzeRuleFactories
import io.legado.app.model.analyzeRule.AnalyzeUrlCore
import kotlinx.coroutines.currentCoroutineContext

/**
 * iOS / 鸿蒙端「直链上传」执行体 (对照 app 端 `DirectLinkUpload.upLoad`、桌面端
 * `DesktopDirectLinkUpload.upLoad`: 三端同一语义, 只差压缩与 HTTP 的实现载体)。
 *
 * 与 jvm 两端的唯一实质差异是压缩: jvm 走 `java.util.zip.ZipOutputStream`,
 * 本端走 nativeMain 既有的纯 Kotlin [NativeZipCodec.zipFiles] (备份/恢复同一套压缩件,
 * 不另起炉灶)。上传后的直链解析与 jvm 端一样用下沉的 [AnalyzeUrlCore] + [AnalyzeRuleFactories],
 * 所以规则语义 (`uploadUrl` 的 `,{json}` 选项、`fileRequest` 占位、`downloadUrlRule`) 三端一致。
 *
 * 错误一律抛出 (未配置 url / 规则缺 body / 空响应都是配置问题, 由调用方 toast 原文),
 * 不做"失败返回 null"式的静默降级。
 */
suspend fun upLoadToDirectLink(fileName: String, file: Any, contentType: String): String {
    val rule = getRuleShared()
    val url = rule.uploadUrl
    if (url.isBlank()) {
        throw NoStackTraceException("上传url未配置")
    }
    val downloadUrlRule = rule.downloadUrlRule
    if (downloadUrlRule.isBlank()) {
        throw NoStackTraceException("下载地址规则未配置")
    }
    var mFileName = fileName
    var mContentType = contentType
    val content = when (file) {
        is String -> file.encodeToByteArray()
        is ByteArray -> file
        else -> throw NoStackTraceException("不支持的上传内容类型: ${file::class.simpleName}")
    }
    var mContent = content
    if (rule.compress && contentType != "application/zip") {
        mFileName = "$fileName.zip"
        mContentType = "application/zip"
        mContent = zipSingleEntry(fileName, content)
    }
    val analyzeUrl = AnalyzeUrlCore(url)
    val res = analyzeUrl.upload(mFileName, mContent, mContentType)
    val analyzeRule = AnalyzeRuleFactories.create().apply {
        setContent(res.body, res.url)
        coroutineContext = currentCoroutineContext()
    }
    // commonMain 的 Closeable 是自定 expect 接口, 没有 kotlin.io 的 use 扩展
    // (AnalyzeUrlCore 同样因此改用 try-finally), 故手写 close。
    return try {
        val downloadUrl = analyzeRule.getString(downloadUrlRule)
        if (downloadUrl.isBlank()) {
            throw NoStackTraceException("上传失败,${res.body}")
        }
        downloadUrl
    } finally {
        analyzeRule.close()
    }
}

/**
 * 单条目 zip (对照 jvm 端 `ZipUtils.zipByteArray(data, fileName)`: 条目名就是 fileName)。
 *
 * [NativeZipCodec.zipFiles] 是路径式 API (备份场景), 故先落临时文件再压再读回, 用完即删:
 * zip 目录用 `cachePath/upload`, 与其它缓存目录隔离, 避免被封面清理路径顺带抹掉。
 */
private fun zipSingleEntry(fileName: String, data: ByteArray): ByteArray {
    val dir = FileUtilsCommon.getPath(FileUtilsCommon.getCachePath(), "upload")
    if (!FileUtilsCommon.createFolderIfNotExist(dir)) {
        throw NoStackTraceException("创建上传临时目录失败:$dir")
    }
    val srcPath = FileUtilsCommon.getPath(dir, fileName)
    val zipPath = FileUtilsCommon.getPath(dir, "$fileName.zip")
    try {
        if (!FileUtilsCommon.writeBytes(srcPath, data)) {
            throw NoStackTraceException("写入上传临时文件失败:$srcPath")
        }
        if (!NativeZipCodec.zipFiles(listOf(srcPath), zipPath)) {
            throw NoStackTraceException("压缩上传内容失败")
        }
        return FileUtilsCommon.readBytes(zipPath)
            ?: throw NoStackTraceException("读取压缩包失败:$zipPath")
    } finally {
        FileUtilsCommon.delete(srcPath)
        FileUtilsCommon.delete(zipPath)
    }
}
