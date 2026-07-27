package io.legado.app.model.fileBook

import io.legado.app.utils.File
import io.legado.app.utils.InputStream
import kotlin.concurrent.Volatile

/**
 * Bitmap 解码压缩跨平台接口 (commonMain)。
 *
 * 供 [CbzFile.extractCover] / EpubFile 封面提取等场景使用, 解耦对
 * `android.graphics.BitmapFactory` / `android.graphics.Bitmap` 的直接依赖。
 *
 * # 实现注册
 * - **Android (app)**: [io.legado.app.model.fileBook.BitmapProviderImpl] 用
 *   `android.graphics.BitmapFactory.decodeStream` + `Bitmap.compress` 实现,
 *   在 `App.onCreate` 经 [BitmapProviders.register] 注入。
 * - **Desktop (desktop)**: `DesktopBitmapProvider` 用 `javax.imageio.ImageIO`
 *   实现, 在 `Main.kt` 注入。
 * - **iOS / 鸿蒙**: `IosBitmapProvider` (UIImage) / `OhosBitmapProvider`,
 *   在各自 ProviderRegistry 注册。
 *
 * 模式参考 [io.legado.app.help.book.BookHelpProviders] /
 * [io.legado.app.help.book.BookStorageProviders]。
 */
interface BitmapProvider {

    /**
     * 从 [input] 解码图片并压缩为 JPEG 写入 [outFile]。
     *
     * 对应 app 端原 [CbzFile.extractCover] 内:
     * ```
     * BitmapFactory.decodeStream(input)?.let { cover ->
     *     FileOutputStream(FileUtils.createFileIfNotExist(coverUrl)).use {
     *         cover.compress(Bitmap.CompressFormat.JPEG, 90, it)
     *     }
     *     cover.recycle()
     * }
     * ```
     *
     * @param input 图片字节输入流 (调用方 use 关闭)
     * @param outFile 目标 JPEG 文件 (实现内部负责创建父目录)
     * @param quality JPEG 压缩质量 (0-100, 与 Bitmap.compress 一致)
     * @return true 解码并写入成功; false 解码失败或 IO 异常
     */
    fun decodeStreamAndCompressToJpeg(input: InputStream, outFile: File, quality: Int): Boolean
}

/**
 * [BitmapProvider] provider 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用 `BitmapProviders.get().decodeStreamAndCompressToJpeg(...)`
 * 替代原 `BitmapFactory.decodeStream(...)` + `Bitmap.compress(...)` 直接调用,
 * 行为完全一致, 仅多一层 provider 间接。
 */
object BitmapProviders {
    @Volatile
    private var impl: BitmapProvider? = null

    /** 宿主启动早期注册一次 (任何 CbzFile/EpubFile 封面提取调用之前)。 */
    fun register(impl: BitmapProvider) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): BitmapProvider = impl ?: error("BitmapProviders not registered")
}
