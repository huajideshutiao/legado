package io.legado.app.utils

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * 复制 [src] 整份内容替换 [dst], 失败时 [dst] 内容照旧并把失败如实抛出 (java.io.IOException)。
 * (与 nativeMain [copyFileAtomically] 同一语义, JVM 端唯一一份实现)。
 *
 * 消费方是"整份覆盖已有文件"的场景 (备份 zip 落备份目录、备份里的配置项恢复到目标路径等):
 * 直接往目标上写字节, 写一半被打断就留下半截文件 (目标旧内容已被截断); 本实现让目标始终是
 * 完整的旧内容或完整的新内容。
 *
 * 复制到 [dst] 同目录的临时件 (名字带随机唯一串) 再交给 [FileUtilsBase.replaceFileAtomically]
 * 就位: 临时件名不依赖时钟, 同一目标的两次复制 (含上次被强杀留下的临时件) 绝不共用一条路径。
 *
 * @return [dst] (便于调用方链式使用)
 */
fun copyFileAtomically(src: File, dst: File): File {
    dst.parentFile?.mkdirs()
    val temp = File(dst.parentFile, "${dst.name}.${randomUUIDString()}$COPY_TEMP_SUFFIX")
    try {
        FileInputStream(src).use { input ->
            FileOutputStream(temp).use { output -> input.copyTo(output) }
        }
        FileUtilsBase.replaceFileAtomically(temp, dst)
    } catch (e: Throwable) {
        temp.delete()
        throw e
    }
    return dst
}

/** 复制临时件后缀 (落在目标同目录, 改名就位才能同卷)。 */
private const val COPY_TEMP_SUFFIX = ".tmp"
