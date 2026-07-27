package io.legado.app.model.fileBook

/** 按 Range 读取字节；fileSize<=0 表示未知大小 */
fun interface RangedSource {
    fun readRange(offset: Long, length: Int, fileSize: Long): ByteArray
}
