package io.legado.app.model.fileBook

import io.legado.app.data.entities.Book

/**
 * EpubFile 跨平台底层资源句柄与 Bitmap 编解码的 expect 声明。
 *
 * # 背景
 * 原 app 端 `EpubFile` 依赖 `android.os.ParcelFileDescriptor` + `BookHelp.getBookPFD` +
 * `AndroidZipFile(pfd, name)` 打开本地 epub, 依赖 `android.graphics.BitmapFactory`/
 * `Bitmap.compress` 处理封面图片。这些 API 在 desktop/jvm 端不存在, 需按平台 actual 拆分。
 *
 * remote (webDav/http) 路径已用跨平台的 `RemoteZipWrapper` (jvmAndAndroidMain) + `RangedSource`,
 * 不需要 expect/actual; 仅本地路径与 Bitmap 编解码走平台 actual。
 *
 * # 设计
 * - [LocalEpubResource] 封装"打开本地 epub 文件并返回 EpubBook + 持有需要 close 的底层句柄",
 *   android actual 内持 `ParcelFileDescriptor` + `AndroidZipFile`, jvm actual 内持 `java.util.zip.ZipFile`。
 *   `close()` 由 EpubFile.finalize 调用, 各平台 actual 自行释放资源。
 * - [decodeBitmap] / [compressBitmap] 拆分原 `BitmapFactory.decodeStream` + `Bitmap.compress`,
 *   android actual 用 BitmapFactory, jvm actual 用 javax.imageio.ImageIO。
 *
 * # 类型说明
 * [epubBook] 返回 `Any?` (而非 `EpubBook?`), 因 EpubBook 在 jvmAndAndroidMain (epublib 未全平台化),
 * commonMain 不可见。各平台 actual 内部返回具体 EpubBook, 调用方 (EpubFile.kt, jvmAndAndroidMain)
 * 用 `as EpubBook?` cast 恢复类型。
 */
expect class LocalEpubResource(book: Book) {

    /**
     * 已解析的 EpubBook (失败返回 null, 由调用方记录错误)。
     *
     * 构造时立即解析 (与原 EpubFile.readEpub 同步行为), 失败不抛异常, 由 [close] 释放已分配资源。
     * 返回 `Any?` 因 EpubBook 类型在 commonMain 不可见, 调用方按平台 actual cast。
     */
    val epubBook: Any?

    /** 释放底层句柄 (ParcelFileDescriptor / ZipFile), 幂等。 */
    fun close()
}

/**
 * 解码字节流为平台原生图片对象 (Android Bitmap / JVM BufferedImage)。
 *
 * 失败返回 null (对应原 `BitmapFactory.decodeStream` 返回 null 的语义)。
 * 返回类型为 `Any?`, 由 [compressBitmap] 按平台 actual 处理。
 */
expect fun decodeBitmap(bytes: ByteArray): Any?

/**
 * 把平台原生图片对象压缩并写入 [destPath]。
 *
 * - Android: `Bitmap.compress(Bitmap.CompressFormat.JPEG/PNG, quality, FileOutputStream)`
 * - JVM: `ImageIO.write(BufferedImage, formatName, File)` (quality 参数忽略, JDK ImageIO 不直接支持)
 *
 * @param bitmap [decodeBitmap] 返回的平台图片对象
 * @param format "JPEG"/"PNG"
 * @param quality 0..100
 * @param destPath 目标文件绝对路径
 * @return 成功 true
 */
expect fun compressBitmap(bitmap: Any?, format: String, quality: Int, destPath: String): Boolean
