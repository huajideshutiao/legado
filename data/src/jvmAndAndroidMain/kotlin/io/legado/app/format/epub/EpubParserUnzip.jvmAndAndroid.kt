package io.legado.app.format.epub

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/**
 * [unzipEpubEntries] 的 JVM/Android actual 实现。
 *
 * 用 `java.util.zip.ZipInputStream` 解压 epub (zip) 字节流为 entry 名 → 字节内容 Map。
 *
 * # 为何 jvmAndAndroidMain 也需要 actual
 * [EpubParser] 在 commonMain 声明 `expect fun unzipEpubEntries`, 所有 target 必须提供 actual。
 * jvmAndAndroidMain 端 [io.legado.app.model.fileBook.EpubFile] 走 epublib (Java) 路径,
 * 当前不调用 [EpubParser]; 但 iOS/鸿蒙端 [EpubParser] 共享编译, 须为 jvm 补 actual 满足契约。
 *
 * 行为对齐 nativeMain [io.legado.app.format.epub.unzipEpubEntries] native actual
 * (NativeZipCodec.unzipToMap): STORED / DEFLATE 均支持, 跳过目录 entry。
 *
 * 模式参考 [io.legado.app.model.fileBook.inflateRaw] jvmAndAndroid actual
 * (同样用 java.util.zip, 字节级与 NativeZipCodec 对拍)。
 */
internal actual fun unzipEpubEntries(zipData: ByteArray): Map<String, ByteArray> {
    val result = LinkedHashMap<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(zipData)).use { zis ->
        while (true) {
            val entry = zis.nextEntry ?: break
            if (!entry.isDirectory) {
                result[entry.name] = zis.readBytes()
            }
            zis.closeEntry()
        }
    }
    return result
}
