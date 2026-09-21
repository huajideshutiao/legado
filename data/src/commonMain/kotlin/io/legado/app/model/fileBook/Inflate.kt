package io.legado.app.model.fileBook

/**
 * 原始 DEFLATE（RFC 1951，无 zlib/gzip 包裹，等价 java `Inflater(nowrap=true)`）解压。
 *
 * zip entry 的压缩数据由 RangedSource 已全量读入内存（见 RemoteZipCore.readCompressed），
 * 故这里用纯函数一次性出内容即可，无需流式 expect class；
 * offset/length 直接吃 Chunk 的切片，省一次拷贝。
 *
 * @param expectedSize 解压后字节数（entry 未压缩大小），用于预分配缓冲；<0 表示未知。
 */
expect fun inflateRaw(
    compressed: ByteArray, offset: Int, length: Int, expectedSize: Int
): ByteArray
