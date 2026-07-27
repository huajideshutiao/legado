package io.legado.app.format.epub

import io.legado.app.help.storage.NativeZipCodec

/**
 * [unzipEpubEntries] 的 iOS/鸿蒙 (Native target) actual 实现。
 *
 * 委托 [NativeZipCodec.unzipToMap] —— 项目已有的纯 Kotlin ZIP 解析 + RFC 1951 inflate 实现
 * (支持 STORED / fixed Huffman / 动态 Huffman), 字节级对齐 jvmAndAndroidMain
 * `java.util.zip.ZipInputStream`。iOS/鸿蒙 Kotlin/Native 标准库不含 `java.util.zip`,
 * 故复用 NativeZipCodec (已下沉 nativeMain, iOS/鸿蒙共用), 避免代码重复。
 *
 * 调用链: [EpubParser.parse] → [unzipEpubEntries] → [NativeZipCodec.unzipToMap]。
 *
 * 模式参考 [io.legado.app.model.fileBook.inflateRaw] native actual
 * (同样委托 NativeZipCodec, 解除 iOS/鸿蒙端 zip 解压 stub 限制)。
 */
internal actual fun unzipEpubEntries(zipData: ByteArray): Map<String, ByteArray> =
    NativeZipCodec.unzipToMap(zipData)
