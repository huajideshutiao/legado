package io.legado.app.format.epub

import io.legado.app.help.storage.NativeZipCodec

/**
 * 解压 epub 字节流为 zip 内 entry 名 → 字节内容 Map。
 *
 * 委托 [NativeZipCodec.unzipToMap] —— 项目已有的纯 Kotlin ZIP 解析 + RFC 1951 inflate 实现
 * (支持 STORED / fixed Huffman / 动态 Huffman)。iOS/鸿蒙 Kotlin/Native 标准库不含
 * `java.util.zip`, 复用 NativeZipCodec (已下沉 nativeMain, iOS/鸿蒙共用), 避免代码重复。
 *
 * 调用链: [EpubParser.parse] → [unzipEpubEntries] → [NativeZipCodec.unzipToMap]。
 */
internal fun unzipEpubEntries(zipData: ByteArray): Map<String, ByteArray> =
    NativeZipCodec.unzipToMap(zipData)
