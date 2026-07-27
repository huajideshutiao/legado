package io.legado.app.utils

@Suppress("unused")
object Utf8BomUtils {
    private val UTF8_BOM_BYTES = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    fun removeUTF8BOM(xmlText: String): String {
        val bytes = xmlText.encodeToByteArray()
        val containsBOM = (bytes.size > 3
                && bytes[0] == UTF8_BOM_BYTES[0]
                && bytes[1] == UTF8_BOM_BYTES[1]
                && bytes[2] == UTF8_BOM_BYTES[2])
        if (containsBOM) {
            return bytes.decodeToString(3, bytes.size)
        }
        return xmlText
    }

    fun removeUTF8BOM(bytes: ByteArray): ByteArray {
        val containsBOM = (bytes.size > 3
                && bytes[0] == UTF8_BOM_BYTES[0]
                && bytes[1] == UTF8_BOM_BYTES[1]
                && bytes[2] == UTF8_BOM_BYTES[2])
        if (containsBOM) {
            // System.arraycopy 逐位等价于 copyOfRange, commonMain 无 System, 改用纯 Kotlin
            return bytes.copyOfRange(3, bytes.size)
        }
        return bytes
    }

    fun hasBom(bytes: ByteArray): Boolean {
        return (bytes.size > 3
                && bytes[0] == UTF8_BOM_BYTES[0]
                && bytes[1] == UTF8_BOM_BYTES[1]
                && bytes[2] == UTF8_BOM_BYTES[2])
    }
}
