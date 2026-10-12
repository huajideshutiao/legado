package io.legado.app.utils

/**
 * 整份写入/替换的临时件名契约 (commonMain)。
 *
 * # 为什么需要它
 *
 * "先写同目录临时件, 再改名就位"的写入方遍布各端 (:data 的 [io.legado.app.help.writeBytesAtomically]
 * 一族、:foundation 的 `copyFileAtomically` 一族、插件图标/扩展安装/TVBox 配置等), 它们都在目标
 * **同目录**生成中间件 (改名才同卷)。进程被强杀时中间件会残留成孤儿件 —— 它既不被任何读取方按名
 * 读取, 也不受任何写入方管理, 却和正常条目一起躺在缓存目录里被目录清点/大小统计/LRU 记账当成条目。
 *
 * 判定必须全端同一份: 名字用 [atomicWriteTempPath] 生成, 清点方用 [isAtomicWriteTempName] 排除,
 * 回收方用 [isOrphanAtomicWriteTemp] 判定"已成孤儿"。逐端各写一套判定会在任一端漂移。
 *
 * # 回收边界: 用户可见目录不自动回收
 *
 * 回收发生在缓存/条目目录的清点动作里 (见 `io.legado.app.help.deleteOrphanAtomicWriteTempFiles`),
 * 导出目录、分享目录、用户选定的保存目录没有这样的清点方: 落在那些目录里的临时件不会被自动回收。
 * 目标在用户可见目录的写入方必须自己保证临时件在每条退出路径上都被删掉 (成功改名、失败、取消),
 * 仅强杀残留这一种情况兜不住。
 *
 * # 为何这名字不会与正常条目冲突
 *
 * 后缀 `.legado-tmp` 不是任何条目名的可能取值: 缓存条目名要么是 key 的 hashCode (纯数字, 见
 * ACache/FileCacheProvider), 要么是 md5/章节序号派生名 (形如 `<md5>.<1-5 位字母数字后缀>` /
 * `00001-<md5>.nb`); 图片后缀解析与封面图集命名都只接受纯字母数字后缀, 含连字符的后缀一律不在其列。
 */
const val ATOMIC_WRITE_TEMP_SUFFIX = ".legado-tmp"

/**
 * [targetPath] 的同目录临时件路径: 目标名 + 随机唯一串 + [ATOMIC_WRITE_TEMP_SUFFIX]。
 *
 * 随机唯一串 (§ [randomUUIDString]) 保证同目标的两次写入绝不共用一条路径 —— 时间戳/进程内序号类
 * 唯一串在双开实例、同毫秒并发下会重合, 共用路径的两次写入会互相截断, 半截内容再被改名换上目标。
 */
fun atomicWriteTempPath(targetPath: String): String =
    "$targetPath.${randomUUIDString()}$ATOMIC_WRITE_TEMP_SUFFIX"

/** 文件名是否为整份写入的临时件 (目录清点/统计条目时必须排除)。 */
fun isAtomicWriteTempName(name: String): Boolean = name.endsWith(ATOMIC_WRITE_TEMP_SUFFIX)

/**
 * 临时件是否已成孤儿 (可以回收)。
 *
 * 判据是"距最后一次改动超过 [ORPHAN_ATOMIC_WRITE_TEMP_MIN_AGE_MILLIS]": 仍在写的那份刚创建,
 * 时间新, 不会被判成孤儿 —— 回收方因此不需要与写入方加锁或握手。
 *
 * 修改时间取不到 ([lastModifiedMillis] <= 0) 时判为"不是孤儿": 判不出来就不删, 避免把正在写的
 * 那份删掉 (代价是这类临时件回收不掉, 但读不到修改时间的文件系统上本来也无法判断)。
 */
fun isOrphanAtomicWriteTemp(
    name: String,
    lastModifiedMillis: Long,
    nowMillis: Long,
): Boolean =
    isAtomicWriteTempName(name) &&
        lastModifiedMillis > 0 &&
        nowMillis - lastModifiedMillis >= ORPHAN_ATOMIC_WRITE_TEMP_MIN_AGE_MILLIS

/**
 * 孤儿临时件的可回收时限: 1 小时。
 *
 * 由单次整份写入的最长耗时决定 —— 落盘的都是缓存条目/配置/图片/书文件副本 (最大到 GB 级文件复制,
 * 分钟级即可完成), 取 1 小时远大于它, 任何设备上"回收"与"写入中"都不重叠。
 */
const val ORPHAN_ATOMIC_WRITE_TEMP_MIN_AGE_MILLIS: Long = 60L * 60 * 1000
