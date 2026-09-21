package io.legado.app.utils

import io.legado.app.constant.AppLog
import java.io.File

/**
 * EncodingDetect 的 File 重载扩展 (jvmAndAndroidMain 专属)。
 *
 * [EncodingDetect] 主体已下沉 commonMain (仅 ByteArray 入参); File 入参的两个重载依赖
 * [java.io.File], 故以扩展函数形式留在 JVM 半区, 跨模块同包名同签名扩展自动合并,
 * 消费方 `EncodingDetect.getEncode(file)` / `EncodingDetect.getEncode(filePath)` 写法不变。
 */
fun EncodingDetect.getEncode(filePath: String): String {
    return getEncode(File(filePath))
}

/**
 * 得到文件的编码
 */
fun EncodingDetect.getEncode(file: File): String {
    val tempByte = getFileBytes(file)
    if (tempByte.isEmpty()) {
        return "UTF-8"
    }
    return getEncode(tempByte)
}

private fun getFileBytes(file: File): ByteArray {
    val byteArray = ByteArray(8000)
    var pos = 0
    try {
        file.inputStream().buffered().use {
            while (pos < byteArray.size) {
                val n = it.read(byteArray, pos, 1)
                if (n == -1) {
                    break
                }
                pos++
            }
        }
    } catch (e: Exception) {
        AppLog.put("读取文件字节失败, 按已读部分判定编码: ${file.absolutePath}", e)
    }
    return byteArray.copyOf(pos)
}
