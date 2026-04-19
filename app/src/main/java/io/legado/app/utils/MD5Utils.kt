package io.legado.app.utils

import java.io.InputStream
import java.security.MessageDigest

object MD5Utils {

    private fun md5(): MessageDigest = MessageDigest.getInstance("MD5")

    fun md5Encode(str: String?): String {
        if (str == null) return ""
        return md5().digest(str.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun md5Encode(inputStream: InputStream): String {
        val digest = md5()
        val buffer = ByteArray(8192)
        var bytesRead: Int
        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
            digest.update(buffer, 0, bytesRead)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun md5Encode16(str: String): String {
        val reStr = md5Encode(str)
        return reStr.substring(8, 24)
    }
}
