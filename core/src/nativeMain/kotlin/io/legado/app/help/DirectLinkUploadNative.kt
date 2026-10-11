package io.legado.app.help

import io.legado.app.help.storage.NativeZipCodec
import io.legado.app.utils.File

/**
 * 直链上传的 iOS/鸿蒙载荷原语 (见 commonMain [DirectLinkUploadPrimitives])。
 *
 * 平台面只有两处: 平台文件载荷是 [io.legado.app.utils.File] (okio 文件面), 单条目 zip 用
 * nativeMain 既有的纯 Kotlin [NativeZipCodec] (与备份/恢复同一套压缩件, 生成物可被
 * JVM/Android/桌面解压)。编排本体在 commonMain [upLoadToDirectLink], 本对象由两端入口注入。
 *
 * [NativeZipCodec.zipFiles] 是路径式 API, 故内存数据先落 `缓存目录/upload` 的临时文件再压再
 * 读回, 用完即删; 该子目录与封面等缓存路径隔离, 不会被清理动作顺带抹掉。
 */
object DirectLinkUploadNativePrimitives : DirectLinkUploadPrimitives {

    override fun readPlatformFileOrNull(file: Any): ByteArray? = (file as? File)?.readBytes()

    override fun zipBytes(data: ByteArray, entryName: String): ByteArray {
        val dir = File(FileUtilsCommon.getPath(FileUtilsCommon.getCachePath(), "upload"))
        dir.mkdirs()
        val src = File(dir, entryName)
        val zip = File(dir, "$entryName.zip")
        try {
            src.parentFile?.mkdirs()
            src.writeBytes(data)
            check(NativeZipCodec.zipFiles(listOf(src.path), zip.path)) { "zip failed: ${zip.path}" }
            return zip.readBytes()
        } finally {
            src.delete()
            zip.delete()
        }
    }
}
