package io.legado.desktop.help

import io.legado.app.constant.AppLog
import io.legado.app.help.file.desktopAppRootDir
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * 数据根互斥锁: 同一数据目录同时只允许一个进程持有。
 *
 * [SingleInstanceGuard] 只解决 GUI 与 GUI 之间的重复启动; 纯命令行模式与 GUI 必须在
 * 同一套互斥里, 否则两边会写同一个 SQLite 库 (互相破坏 -wal/-shm) 并争同一组 Web 端口。
 *
 * 实现用 OS 级文件锁而不是 pid 探测: 进程崩溃/被强杀时内核自动释放, 不存在"残留锁要判定
 * 是否陈旧"的分支 (pid 复用会让那种判定不可靠)。
 *
 * 调用顺序: 必须在 [io.legado.desktop.DesktopCore.initRuntimeEnvironment] 之后 —— 锁文件落在
 * [desktopAppRootDir] 下, 而该根目录进程内只解析一次, 提前读会把便携目录定位歪 (同
 * [SingleInstanceGuard] 的既有约束)。
 */
object DataRootLock {

    private const val LOCK_FILE_NAME = "datadir.lock"

    private var lockFile: RandomAccessFile? = null
    private var lock: FileLock? = null

    /** 本进程是否持有锁。 */
    @Volatile
    var isHeld: Boolean = false
        private set

    /**
     * 尝试获取锁 (非阻塞)。
     *
     * @return true = 获取成功并持有到 [release] 或进程退出;
     *   false = 已被其他进程或本进程更早的调用持有 (调用方据此拒绝启动)
     */
    fun tryAcquire(): Boolean {
        if (isHeld) return false
        val target = File(desktopAppRootDir(), LOCK_FILE_NAME)
        target.parentFile?.mkdirs()
        return runCatching {
            val file = RandomAccessFile(target, "rw")
            // 同进程重复 tryLock 会抛 OverlappingFileLockException, 与"他进程持有"同属拒绝启动
            val acquired = try {
                file.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (acquired == null) {
                file.close()
                false
            } else {
                lockFile = file
                lock = acquired
                isHeld = true
                true
            }
        }.onFailure {
            AppLog.put("数据目录互斥锁获取失败: ${it.localizedMessage}", it)
        }.getOrDefault(false)
    }

    /** 释放锁 (幂等; 进程退出 hook 里调用)。 */
    fun release() {
        if (!isHeld) return
        isHeld = false
        runCatching { lock?.release() }
        runCatching { lockFile?.close() }
        lock = null
        lockFile = null
        runCatching { File(desktopAppRootDir(), LOCK_FILE_NAME).delete() }
    }
}
