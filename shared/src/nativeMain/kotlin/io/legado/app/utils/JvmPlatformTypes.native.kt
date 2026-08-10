package io.legado.app.utils

import io.legado.app.help.http.KmpResponseBody
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * JVM 专属类型的 iOS/鸿蒙 actual 实现。
 *
 * 详见 commonMain/utils/JvmPlatformTypes.kt expect 注释。
 * 纯 Kotlin 包装: 不依赖 platform.* 框架, iOS/鸿蒙代码完全一致。
 *
 * - [URL] 内部用 String 字段保存 URL 文本
 * - [InputStream] 纯 Kotlin ByteArrayInputStream 风格 (内存数据源)
 * - [Closeable] 纯 Kotlin 接口, close() 默认空实现
 *
 * [toInputStream]: ByteArray 内存 ByteArrayInputStream 风格 (与 JVM ByteArrayInputStream 行为对齐)。
 *
 * KP4 OkHttp 跨平台修复: [byteStreamAsInput] 接收者改为 [Any] (commonMain expect 不引用 okhttp3.ResponseBody);
 * native 端委托 KmpResponseBody.byteStream(), AnalyzeUrlCore 的流式路径在 iOS/鸿蒙可用。
 */
actual class URL actual constructor(val url: String) {
    actual override fun toString(): String = url

    actual fun toExternalForm(): String = url

}

internal actual fun URL.urlQuery(): String? =
    url.substringAfter('?', "").substringBefore('#').takeIf { it.isNotEmpty() }

actual abstract class InputStream {
    abstract fun read(): Int

    actual open fun read(b: ByteArray): Int = read(b, 0, b.size)

    actual open fun read(b: ByteArray, off: Int, len: Int): Int {
        var i = 0
        while (i < len) {
            val v = read()
            if (v == -1) break
            b[off + i] = v.toByte()
            i++
        }
        return if (i == 0) -1 else i
    }

    // java.io.InputStream.skip 语义: 读取丢弃, 返回实际跳过字节数
    actual open fun skip(n: Long): Long {
        if (n <= 0) return 0
        var remaining = n
        val buf = ByteArray(minOf(remaining, 8192L).toInt())
        while (remaining > 0) {
            val r = read(buf, 0, minOf(remaining, buf.size.toLong()).toInt())
            if (r < 0) break
            remaining -= r
        }
        return n - remaining
    }

    // java.io.InputStream.available 基类默认 0, 子类按剩余量 override
    actual open fun available(): Int = 0

    actual open fun close() {}
}

/**
 * java.io.File 的 native actual: 用 okio [FileSystem.SYSTEM] 实现真实文件操作。
 *
 * Kotlin/Native 标准库没有 `kotlin.io.File` (klib dump 实证: kotlin.io 包仅 print/println/readln),
 * 故 native 三源集需要本类承担文件面。okio 已是 commonMain api 依赖且发布全 native 变体,
 * 用它而非手写 posix 的关键原因: `stat` 结构体字段名不跨平台 (Apple `st_mtimespec` /
 * Linux-OHOS `st_mtim`), 直接引用会让 iosMain 与 ohosMain 无法共用同一份 nativeMain 代码。
 *
 * 成员签名与 java.io.File 对齐 (函数/属性形态一致), 便于消费点仅改 import 一行。
 *
 * # 与 java.io.File 的行为差异
 * - 所有操作失败时返回 false / null 而非抛异常 (okio 抛 IOException, 本类内部 runCatching 吞掉),
 *   与 java.io.File 的布尔返回风格一致。
 * - [absolutePath] 用 okio canonicalize, 失败 (如文件不存在) 回落到 [path] 原样;
 *   java.io.File.getAbsolutePath 仅拼接 CWD 不解析符号链接, 故本实现在文件存在时更接近
 *   getCanonicalPath。项目内消费点均传绝对路径, 差异不可见。
 * - [length] 目录返回 0 (java.io.File 对目录返回值平台相关, 不作依赖)。
 * - [renameTo] 用 okio atomicMove (POSIX rename, 目标存在时原子替换);
 *   java.io.File.renameTo 目标存在时行为平台相关。
 * - [listFiles] 返回 null 的条件是路径不存在或不是目录, 与 java.io.File 一致。
 * - [mkdirs] 目录已存在时返回 true (java.io.File 返回 false, 因为"未创建新目录");
 *   全部消费点都按 "确保目录存在" 语义使用 (先 exists 判断或忽略返回值), 语义更贴合。
 * - [delete] 文件不存在时返回 true (java.io.File 返回 false); 消费点均为清理场景
 *   (删缓存/删 tmp), "目标已不存在" 即达成目的。
 * - [walkTopDown] 返回 Sequence 而非 kotlin.io 的 FileTreeWalk, 且子目录遍历为 LIFO 顺序;
 *   唯一消费点 (NativeBookStorage 统计目录体积) 只做累加, 不依赖顺序。
 */
actual class File actual constructor(val path: String) {

    /** 对齐 java.io.File(parent, child) 构造器。 */
    constructor(parent: String, child: String) : this(
        if (parent.endsWith("/")) "$parent$child" else "$parent/$child"
    )

    /** 对齐 java.io.File(parent: File, child: String) 构造器。 */
    constructor(parent: File, child: String) : this(parent.path, child)

    private val okioPath: Path get() = path.toPath()

    private val fs: FileSystem get() = FileSystem.SYSTEM

    /** java.io.File.getName: 末段名; 根路径返回空串。 */
    val name: String get() = okioPath.name

    /** java.io.File.getParent: 父路径字符串, 无父返回 null。 */
    val parent: String? get() = okioPath.parent?.toString()

    /** java.io.File.getParentFile: 父目录 File, 无父返回 null。 */
    val parentFile: File? get() = okioPath.parent?.let { File(it.toString()) }

    /** java.io.File.getAbsolutePath: 见类注释差异说明 (canonicalize 失败回落 path)。 */
    val absolutePath: String
        get() = runCatching { fs.canonicalize(okioPath).toString() }.getOrDefault(path)

    /** java.io.File.exists */
    fun exists(): Boolean = runCatching { fs.exists(okioPath) }.getOrDefault(false)

    /** java.io.File.isFile: 存在且为普通文件。 */
    val isFile: Boolean
        get() = runCatching { fs.metadataOrNull(okioPath)?.isRegularFile }.getOrNull() ?: false

    /** java.io.File.isDirectory: 存在且为目录。 */
    val isDirectory: Boolean
        get() = runCatching { fs.metadataOrNull(okioPath)?.isDirectory }.getOrNull() ?: false

    /** java.io.File.length: 字节数; 不存在/目录/读取失败返回 0。 */
    fun length(): Long =
        runCatching { fs.metadataOrNull(okioPath)?.size }.getOrNull() ?: 0L

    /** java.io.File.lastModified: epoch millis; 不可得返回 0。 */
    fun lastModified(): Long =
        runCatching { fs.metadataOrNull(okioPath)?.lastModifiedAtMillis }.getOrNull() ?: 0L

    /** java.io.File.mkdirs: 递归创建目录; 已存在视为成功 (与 okio createDirectories 语义一致)。 */
    fun mkdirs(): Boolean = runCatching {
        fs.createDirectories(okioPath, mustCreate = false)
        true
    }.getOrDefault(false)

    /** java.io.File.createNewFile: 不存在则创建空文件, 已存在返回 false。 */
    fun createNewFile(): Boolean {
        if (exists()) return false
        return runCatching { fs.write(okioPath) { }; true }.getOrDefault(false)
    }

    /** java.io.File.delete: 删除单个文件或空目录; 失败返回 false (非递归)。 */
    fun delete(): Boolean =
        runCatching { fs.delete(okioPath, mustExist = false); true }.getOrDefault(false)

    /** kotlin.io.File.deleteRecursively: 递归删除目录及子项。 */
    fun deleteRecursively(): Boolean = runCatching {
        fs.deleteRecursively(okioPath, mustExist = false)
        true
    }.getOrDefault(false)

    /** java.io.File.renameTo: okio atomicMove (POSIX rename, 见类注释差异说明)。 */
    fun renameTo(dest: File): Boolean =
        runCatching { fs.atomicMove(okioPath, dest.okioPath); true }.getOrDefault(false)

    /** java.io.File.listFiles: 子项数组; 路径不存在或非目录返回 null。 */
    fun listFiles(): Array<File>? =
        runCatching { fs.listOrNull(okioPath)?.map { File(it.toString()) }?.toTypedArray() }
            .getOrNull()

    /** java.io.File.listFiles(filter): 按谓词过滤子项。 */
    fun listFiles(filter: (File) -> Boolean): Array<File>? =
        listFiles()?.filter(filter)?.toTypedArray()

    /** kotlin.io.File.readBytes: 全量读取; 失败抛 okio IOException (与 JVM 抛 IOException 对齐)。 */
    fun readBytes(): ByteArray = fs.read(okioPath) { readByteArray() }

    /** kotlin.io.File.writeBytes: 覆盖写入。 */
    fun writeBytes(bytes: ByteArray) {
        fs.write(okioPath) { write(bytes) }
    }

    /** kotlin.io.File.readText: UTF-8 全量读取。 */
    fun readText(): String = fs.read(okioPath) { readUtf8() }

    /** kotlin.io.File.writeText: UTF-8 覆盖写入。 */
    fun writeText(text: String) {
        fs.write(okioPath) { writeUtf8(text) }
    }

    /**
     * kotlin.io.File.copyTo: 复制到目标。
     * [overwrite] false 且目标已存在时返回目标不做写入 (对齐 kotlin.io 抛 FileAlreadyExistsException
     * 的差异见类注释: 本实现不抛, 由调用方以 overwrite=true 使用)。
     */
    fun copyTo(target: File, overwrite: Boolean = false): File {
        if (!overwrite && target.exists()) return target
        target.parentFile?.mkdirs()
        fs.copy(okioPath, target.okioPath)
        return target
    }

    /**
     * kotlin.io.File.walkTopDown: 自顶向下遍历自身与全部子项。
     * 与 kotlin.io 的 FileTreeWalk 一致地把自身作为首个元素产出。
     */
    fun walkTopDown(): Sequence<File> = sequence {
        yield(this@File)
        val stack = ArrayDeque<File>()
        if (isDirectory) stack.addLast(this@File)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                yield(child)
                if (child.isDirectory) stack.addLast(child)
            }
        }
    }

    /** 对齐 java.io.File.toString (返回路径)。 */
    override fun toString(): String = path

    override fun equals(other: Any?): Boolean = other is File && other.path == path

    override fun hashCode(): Int = path.hashCode()
}

actual interface Closeable {
    actual fun close()
}

/**
 * ByteArray → InputStream 转换: 内存 ByteArrayInputStream 风格。
 * actual: 纯 Kotlin 包装, 内部 pos 计数, 与 JVM ByteArrayInputStream 行为对齐。
 */
internal actual fun ByteArray.toInputStream(): InputStream = ByteArrayInputStream(this)

/**
 * ResponseBody → InputStream 转换: 委托 [KmpResponseBody.byteStream]
 * (iOS/鸿蒙 actual 均已在内存字节缓存上实现, 可多次读, 语义同 OkHttp)。
 *
 * KP4: 接收者为 [Any] (commonMain expect 不引用 okhttp3.ResponseBody), 故此处强转。
 */
internal actual fun Any.byteStreamAsInput(): InputStream = (this as KmpResponseBody).byteStream()

/** 纯 Kotlin ByteArrayInputStream 风格, 用于 toInputStream。 */
private class ByteArrayInputStream(
    private val buffer: ByteArray,
    private var pos: Int = 0,
    private val count: Int = buffer.size,
) : InputStream() {
    override fun read(): Int {
        return if (pos >= count) -1 else (buffer[pos++].toInt() and 0xFF)
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (pos >= count) return -1
        val n = minOf(len, count - pos)
        buffer.copyInto(b, off, pos, pos + n)
        pos += n
        return n
    }

    // 与 JVM ByteArrayInputStream.skip/available 语义一致
    override fun skip(n: Long): Long {
        val k = minOf(n.coerceAtLeast(0), (count - pos).toLong())
        pos += k.toInt()
        return k
    }

    override fun available(): Int = count - pos
}

actual fun Throwable.isSecurityException(): Boolean =
    this is io.legado.app.exception.SecurityException

actual fun String.platformIntern(): String = this
