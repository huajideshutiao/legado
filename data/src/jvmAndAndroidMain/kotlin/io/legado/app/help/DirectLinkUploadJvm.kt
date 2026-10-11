package io.legado.app.help

import io.legado.app.utils.compress.ZipUtils
import java.io.File

/**
 * 直链上传的 JVM 载荷原语 (Android 与桌面共用, 见 commonMain [DirectLinkUploadPrimitives])。
 *
 * 平台面只有两处: 平台文件载荷是 [java.io.File], 单条目 zip 走 [ZipUtils] (java.util.zip)。
 * 编排本体在 commonMain [upLoadToDirectLink], 本对象由两端入口注入。
 */
object DirectLinkUploadJvmPrimitives : DirectLinkUploadPrimitives {

    override fun readPlatformFileOrNull(file: Any): ByteArray? = (file as? File)?.readBytes()

    override fun zipBytes(data: ByteArray, entryName: String): ByteArray =
        ZipUtils.zipByteArray(data, entryName)
}
