package io.legado.app.help.book

import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookChapter
import io.legado.app.help.FileUtilsCommon
import kotlin.concurrent.Volatile

/**
 * 书籍图片缓存 I/O 跨平台抽象。
 *
 * 安卓端 [io.legado.app.help.book.BookHelp] 的 saveImage / getImage /
 * isImageExist / hasImageContent 等方法重 Android 依赖 (BitmapFactory 校验 /
 * ImageUtils.decode / AnalyzeUrl.getByteArrayAwait), 留 app 端。本接口仅暴露
 * 图片缓存的落盘、寻址与存在性原语 (不含下载编排: 下载统一走 ui 模块的
 * [io.legado.app.help.book.BookImageDownloadShared], 由其调本接口写字节),
 * 供四端 commonMain 业务调用。
 *
 * 实现注册:
 * - Android: app 端启动早期调用 [BookImageStorageProviders.register] 注入
 *   [io.legado.app.help.book.AndroidBookImageStorage] (委托 [io.legado.app.help.book.BookHelp])。
 * - 桌面 JVM: desktop 模块启动时注入 [io.legado.app.help.book.JvmBookImageStorage]
 *   (java.nio.file.Path + OkHttp 实现)。
 * - iOS / 鸿蒙: 共用 nativeMain [io.legado.app.help.book.NativeBookImageStorage]
 *   (`kotlin.io.File` 实现)。
 *
 * 模式参考 [BookStorageProviders] / [io.legado.app.help.book.BookHelpProviders]。
 */
interface BookImageStorage {

    /**
     * 保存单张图片字节到缓存, 返回保存后的本地路径 (失败返回 null)。
     *
     * [url] 用于派生文件名 (MD5(url).suffix), 避免重名。
     * 实现内部应做图片有效性校验 (损坏字节返回 null)。
     */
    fun saveImage(book: Book, chapter: BookChapter, url: String, bytes: ByteArray): String?

    /**
     * 取图片本地路径; 文件不存在返回 null。
     *
     * 注意: 与 [saveImage] 的 [url] 参数一致, 同一 url 解析到同一文件名。
     */
    fun getImagePath(book: Book, chapter: BookChapter, url: String): String?

    /**
     * 判断图片是否已缓存到本地。
     *
     * 对应 Android 端 [io.legado.app.help.book.BookHelp.isImageExist]。四端同一判据
     * [isCachedImageUsable] (存在且非空), 与缓存读取侧 ("读出非空才算命中") 一致:
     * 判据不一致时, 零字节缓存会变成"读侧判不可用、存在性判可用"的死局 (既读不出也不重下)。
     */
    fun isImageExist(book: Book, chapter: BookChapter, url: String): Boolean

    /**
     * 检测该书是否有图片缓存目录 (用于漫画类书籍判断)。
     *
     * 对应 Android 端 [io.legado.app.help.book.BookHelp.hasImageContent] 的
     * "目录是否存在" 部分 (不校验图片有效性)。
     */
    fun hasImageContent(book: Book): Boolean

    /**
     * 清空指定书的图片缓存 (整本书 images 目录)。
     */
    fun clearCache(book: Book)

    /**
     * 校验字节流是否为有效图片 (栅格图或 SVG)。
     *
     * 对照 app 端 `BookHelp.checkImage`: 先按栅格图校验, 失败再试 SVG; 都不是则 false。
     * 仅供图片下载编排 ([io.legado.app.help.book.BookImageDownloadShared]) 判定
     * “数据异常”日志用, 校验失败的字节仍会经 [writeImageBytes] 落盘。
     */
    fun checkImageBytes(bytes: ByteArray): Boolean

    /**
     * 将 (已解密) 原始字节写入图片缓存, 不做校验。
     *
     * 对照 app 端 `BookHelp.writeImage`: 命名/目录派生与 [saveImage] 同源, 但
     * 字节无效也照写 (安卓行为: 校验失败只记日志, 仍落盘避免反复重新下载)。
     */
    fun writeImageBytes(book: Book, chapter: BookChapter, url: String, bytes: ByteArray)
}

/**
 * 图片缓存文件的可用判据 (四端同一份): 存在且非空。
 *
 * 落盘走整份写入 (同目录临时件 + 改名就位) 后目标名上只会有完整内容, 剩下的退化情况是零字节文件
 * (空响应体也会被照写); 读侧缓存读取同样以非空为可用 —— 两侧判据不一致时会出现
 * "读侧判不可用、存在性判可用"的死局: 图片既显示不出来, 也不会重新下载。
 */
fun isCachedImageUsable(path: String): Boolean = FileUtilsCommon.length(path) > 0

/**
 * [BookImageStorage] provider 容器。宿主启动早期注册一次。
 *
 * shared 内访问点用 `BookImageStorageProviders.get().saveImage(...)` 替代
 * 原 `BookHelp.saveImage(...)`, 行为完全一致, 仅多一层 provider 间接。
 */
object BookImageStorageProviders {
    @Volatile
    private var impl: BookImageStorage? = null

    /** 宿主启动早期注册一次 (任何 BookContent 调用之前)。 */
    fun register(impl: BookImageStorage) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): BookImageStorage = impl ?: error("BookImageStorageProviders not registered")
}
