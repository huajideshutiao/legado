package io.legado.app.utils

import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import okio.buffer
import okio.use

/**
 * 用 [src] 整份替换 [dst], 任一步失败都保持 [dst] 已有的内容可用
 * (与 jvmAndAndroidMain [FileUtilsBase.replaceFileAtomically] 同一语义, iOS/鸿蒙唯一一份实现)。
 *
 * 消费方是"整份覆盖已有文件"的场景 (书文件导入副本、备份 zip 落用户目录等): 直接往目标上写字节,
 * 写一半被打断就留下半截文件 (目标旧内容已被截断); 本实现让目标始终是完整的旧内容或完整的新内容。
 *
 * 流程: 直接改名 (POSIX rename, 目标已存在时原子替换) → 改名被拒时原文件退到
 * [REPLACE_BACKUP_SUFFIX] 备份名让出目标名后重试 → 重试成功才清掉备份, 重试失败把原文件改回原位。
 * 全程不删原文件。
 *
 * [src] 与 [dst] 必须同卷 (调用方的临时件都落在目标目录): 跨卷只剩整份复制一条路, 复制中断会留下
 * 半截目标。[src] 的内容校验由调用方在调用前完成 —— 改名不复制字节, 校验过的临时件原样成为目标。
 *
 * 失败抛 [IOException] (消息给出失败环节与原文件去向), [src] 保持原样由调用方清理。
 */
fun replaceFileAtomically(src: File, dst: File) {
    dst.parentFile?.mkdirs()
    val backup = siblingOf(dst, REPLACE_BACKUP_SUFFIX)
    if (backup.isFile && dst.exists()) {
        backup.delete()
    }
    if (!src.renameTo(dst)) {
        if (!dst.exists()) {
            throw IOException("替换落盘失败: ${dst.path}")
        }
        if (!dst.renameTo(backup)) {
            throw IOException("替换落盘失败, 原文件被占用: ${dst.path}")
        }
        if (!src.renameTo(dst)) {
            if (!backup.renameTo(dst)) {
                throw IOException("替换落盘失败, 原文件在 ${backup.path}")
            }
            throw IOException("替换落盘失败, 原文件未动: ${dst.path}")
        }
    }
    backup.delete()
}

/**
 * 复制 [src] 整份内容替换 [dst], 失败时 [dst] 内容照旧并抛 [IOException]。
 *
 * 复制到 [dst] 同目录的临时件 (名按共享契约 [atomicWriteTempPath] 生成) 再 [replaceFileAtomically]
 * 就位: 同一目标的两次复制 (含上次被强杀留下的临时件) 绝不共用一条路径; 复制中途中断只留下临时件,
 * 不会把目标写成半截。被强杀残留的临时件是孤儿件, 由目录清点方按 [isAtomicWriteTempName] 排除并回收。
 *
 * @return [dst] (便于调用方链式使用)
 */
fun copyFileAtomically(src: File, dst: File, parent: Job? = null): File {
    dst.parentFile?.mkdirs()
    val temp = File(atomicWriteTempPath(dst.path))
    try {
        // 复制循环每块前核父 (kotlin.io 的 File.copyTo 不给逐块检查点, 这里按同一缓冲语义自读自写):
        // 撤销不必等整份复制完
        FileSystem.SYSTEM.source(src.path.toPath()).buffer().use { input ->
            FileSystem.SYSTEM.sink(temp.path.toPath()).buffer().use { output ->
                val buffer = ByteArray(ATOMIC_COPY_BUFFER_SIZE)
                while (true) {
                    parent?.ensureActive()
                    val read = input.read(buffer, 0, buffer.size)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                }
            }
        }
        // 进入正式提交 (替换正式名) 之前最后核一次: 已撤销不得动正式件
        parent?.ensureActive()
        replaceFileAtomically(temp, dst)
    } catch (e: Throwable) {
        temp.delete()
        throw e
    }
    return dst
}

/** 替换时原文件让位用的后缀: 目标名让给新件, 原件原样留在这份备份里。 */
private const val REPLACE_BACKUP_SUFFIX = ".bak"

/** [dst] 的同目录兄弟件 (无父路径时按当前目录)。 */
private fun siblingOf(dst: File, suffix: String): File {
    val parent = dst.parent
    return if (parent.isNullOrEmpty()) File(dst.name + suffix) else File(parent, dst.name + suffix)
}
