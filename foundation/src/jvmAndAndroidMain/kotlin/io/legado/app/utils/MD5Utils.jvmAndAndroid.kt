package io.legado.app.utils

import java.io.InputStream

actual object MD5Utils {

    actual fun md5Encode(str: String?): String = MD5UtilsCore.md5Encode(str)

    /** 字节数组全量 MD5 (commonMain expect 成员, 委托共享 Md5Digest) */
    actual fun md5Encode(bytes: ByteArray): String = MD5UtilsCore.md5Encode(bytes)

    /** InputStream 流式重载：JVM 半区附加成员，委托 common 的 Md5Digest */
    fun md5Encode(inputStream: InputStream): String {
        val digest = Md5Digest()
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        return digest.digest().toHexLower()
    }

    actual fun md5Encode16(str: String): String = MD5UtilsCore.md5Encode16(str)
}
