package io.legado.app.ui.book.read.page

import io.legado.app.constant.AppLog
import io.legado.app.help.casUpdate
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import org.jetbrains.skia.Typeface

/**
 * 正文字体文件来源状态: 度量侧已加载的字形按文件标识复用, 标识由真正读过文件的一侧登记。
 *
 * 受沙盒约束的端 (iOS 的 security-scoped 授权、鸿蒙的目录授权) 在授权租约之外读不到选定目录里的
 * 字体文件, 文件标识只能随读盘一起取 —— 度量侧若自行探测标识, 每次探测都要重新取一次授权
 * (iOS 还要过文件协调器, 云盘字体未下载时会拖住首排)。故标识不是度量侧探测的, 而由绘制侧在读完
 * 字体文件后登记: 绘制侧每次重读字体 (新阅读会话、换字体文件) 都留下本次读到的标识, 或留下
 * 读不到的事实, 度量侧据此判断同一路径上已加载的字形是否仍对应当前文件 ——
 *
 * - 登记未变: 直接复用已加载字形, 不取授权、不读盘;
 * - 登记变了: 重新取授权读盘 (文件确实变了, 重读不可省);
 * - 登记为读不到: 与绘制侧一致回落默认字形, 不自行取授权。
 *
 * 于是排版重建 (首排 / 视口重排 / 段评补排) 只在文件标识变化时才付一次授权与整文件读盘,
 * 同一份内容在整个进程内只加载一次; 不落任何磁盘副本。
 */
internal object ReaderFontSource {

    /** 字体文件标识: 大小 + 最后修改时刻 (毫秒), 两者都不变即认为文件内容未变。 */
    data class FileIdentity(val size: Long, val lastModified: Long)

    /** 绘制侧一次读取字体文件的结果。 */
    sealed interface Read {
        data class Loaded(val identity: FileIdentity) : Read

        /** 未取到授权 / 文件不存在 / 读盘或建字形失败。 */
        data object Unreadable : Read
    }

    /** 一个路径上按某个登记加载出的结果; [typeface] 为 null 表示那次加载失败。 */
    private class LoadedTypeface(val read: Read?, val typeface: Typeface?)

    /**
     * 已加载字形的条目上界: 正文与标题共用同一条, 用户换字体时旧路径各占一条, 一次阅读只用一份字体。
     * 每条持有一个字形 (字体级内存), 故必须有界; 淘汰按加入顺序, 被淘汰的路径下次用到时重新加载。
     */
    private const val LOADED_CACHE_SIZE = 4

    /** 绘制侧登记的读取结果: 键只会是用户在本进程内选过的字体路径, 每条仅两个长整数。 */
    private val reads = atomic<Map<String, Read>>(emptyMap())

    private val loadLock = SynchronizedObject()

    private val loadedTypefaces = LinkedHashMap<String, LoadedTypeface>(LOADED_CACHE_SIZE)

    /** 绘制侧读到字体文件: 登记本次读到的标识。 */
    fun onRead(path: String, identity: FileIdentity) {
        reads.casUpdate { it + (path to Read.Loaded(identity)) }
    }

    /** 绘制侧没读到字体文件: 登记读不到。 */
    fun onUnreadable(path: String) {
        reads.casUpdate { it + (path to Read.Unreadable) }
    }

    /**
     * 取 [path] 上对应当前登记的字体字形, 需要重新加载时由 [load] 取授权读盘。
     *
     * 返回 null 表示这份字体用不了 (加载失败或绘制侧已判定读不到), 调用方回落默认字形。
     *
     * 加载在锁内执行: 首排时并发的章节排版会同时到达同一路径, 持锁让后来者等这次加载完成并复用
     * 结果, 免得同一个文件按 worker 数各取一次授权读一次盘。绘制侧只写 [reads] (无锁), 不等此锁,
     * 故组合期的字体加载不会被这里的读盘挡住。
     */
    fun typefaceFor(path: String, load: () -> Typeface?): Typeface? = synchronized(loadLock) {
        val read = reads.value[path]
        loadedTypefaces[path]?.let { if (it.read == read) return it.typeface }
        if (read == Read.Unreadable) return remember(path, read, null)
        remember(path, read, loadTypeface(path, load))
    }

    /** 取授权读盘建字形; 取不到字形或抛异常都算加载失败, 记一条可查日志, 回落默认字形由调用方处理。 */
    private fun loadTypeface(path: String, load: () -> Typeface?): Typeface? = try {
        val typeface = load()
        if (typeface == null) {
            AppLog.put("正文字体加载失败, 回落默认字体: $path\n字体文件读不到或解析不出字形")
        }
        typeface
    } catch (e: Throwable) {
        AppLog.put("正文字体加载失败, 回落默认字体: $path\n${e.message}", e)
        null
    }

    /** 登记本次加载结果 (含失败) 并回传字形; 超出上界时淘汰最早登记的一条。 */
    private fun remember(path: String, read: Read?, typeface: Typeface?): Typeface? {
        if (path !in loadedTypefaces && loadedTypefaces.size >= LOADED_CACHE_SIZE) {
            loadedTypefaces.remove(loadedTypefaces.keys.first())
        }
        loadedTypefaces[path] = LoadedTypeface(read, typeface)
        return typeface
    }
}
