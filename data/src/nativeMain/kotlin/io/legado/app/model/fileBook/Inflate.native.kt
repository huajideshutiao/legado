package io.legado.app.model.fileBook

import io.legado.app.help.storage.NativeZipCodec

/**
 * [inflateRaw] 的 iOS/鸿蒙 actual 实现 (nativeMain 中间源集共用)。
 *
 * 详见 commonMain/model/fileBook/Inflate.kt expect 注释。
 * iOS/鸿蒙两端 actual 实现完全一致 (均委托 [NativeZipCodec.inflate]), 下沉到 nativeMain 共用。
 *
 * # 方案
 * 复用 [NativeZipCodec.inflate] —— 项目已有的纯 Kotlin RFC 1951 inflate 实现
 * (支持 stored / fixed Huffman / 动态 Huffman 三种块), 字节级对齐
 * jvmAndAndroidMain `java.util.zip.Inflater(nowrap=true)`。
 *
 * # 背景
 * iOS/鸿蒙 Kotlin/Native target 标准库不含 `java.util.zip` (JVM-only);
 * iOS 默认绑定不含 `platform.Compression` / `platform.zlib` (需 cinterop 配置),
 * 鸿蒙 (linuxArm64) 也不提供 `@ohos.zlib` 的 Kotlin/Native 绑定 (需 napi 桥接)。
 * 项目约束 (不改 build.gradle / 不加 cinterop / 不引入新库) 排除
 * minizip / kotlinx-io-compression 等方案。
 * [NativeZipCodec] 内的 inflate 原本为备份/恢复 zip 解压实现 (private),
 * 此处提升可见性至 internal 复用, 避免代码重复, 符合 "优先复用项目已有模块" 约束。
 * (NativeZipCodec 已下沉 nativeMain, iOS/鸿蒙共用)
 *
 * # 调用链
 * RemoteZipCore.readDecompressed -> inflateRaw 解压 zip entry 压缩数据 (method=8)。
 *
 * # 旧 stub 注释勘误
 * 旧注释提及 "RemoteBookLocator 已 stub, 此函数不会被运行时调用" 已过时
 * (NativeLocalBookLocator 已真实实现, 见 help/book/NativeLocalBookLocator.native.kt);
 * 现补齐解除潜在运行时 UnsupportedOperationException 风险。
 *
 * @param expectedSize 解压后字节数; 当前仅作文档/未来容量优化提示,
 *   [NativeZipCodec.inflate] 内部用 length*3 启发式预分配, 即使 < 0 也能正确解压
 *   (ByteBuilder 按需 1.5 倍扩容), 行为与 jvmAndAndroid 一致 (后者亦仅作初始容量)。
 */
actual fun inflateRaw(
    compressed: ByteArray, offset: Int, length: Int, expectedSize: Int
): ByteArray = NativeZipCodec.inflate(compressed, offset, length)
