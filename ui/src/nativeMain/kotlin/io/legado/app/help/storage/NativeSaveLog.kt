package io.legado.app.help.storage

import io.legado.app.constant.AppLog
import io.legado.app.constant.LOG_DIR_NAME
import io.legado.app.help.book.bookSaveSubDir
import io.legado.app.help.book.effectiveBookSaveDir
import io.legado.app.help.coroutine.IoDispatcher
import io.legado.app.help.coroutine.mainDispatcher
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.log.NativeAppLogStore
import io.legado.app.help.toast.Toasters
import io.legado.app.utils.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * iOS/鸿蒙"保存日志"的打包与落盘 (对照原版 `AboutFragment.saveLog` 的三段式)。
 *
 * 原版逐字行为: 书籍保存位置未设置 → toast「未设置书籍保存位置」并中止; 未开记录日志 → toast 提醒
 * (不中止); 把运行日志与崩溃日志 (同在 `{filesDir}/log`) 压成 `logs.zip` 写进**用户选定的
 * 书籍保存位置下的 log 目录** → toast
 * 「已保存至书籍保存位置」; 失败只记 AppLog。
 *
 * 与 Android 的差别只有两处, 都是平台事实而非设计选择:
 * - 目录取自 native 的 filesDir/cacheDir, 而不是 `externalCache`
 * - 没有 logcat (iOS/鸿蒙不提供该命令), 原版那一路无对应物, 不伪造空文件
 *
 * # 为什么放在 nativeMain
 * 落盘要走 [copyBackupIntoUserDir] (与备份 zip 同一条授权通道), 而它声明在 nativeMain;
 * 调用方 (各端能力对象) 在 iosMain/ohosMain, 可见本源集。
 */
internal object NativeSaveLog {

    private const val ZIP_NAME = "logs.zip"

    /** 落盘目录 = 书籍保存位置 (与备份 zip/导出书籍同一处)。 */
    private fun backupPathOrNull(): String? = effectiveBookSaveDir()

    /**
     * 在 [scope] 上跑完整套"保存日志"并把结果 toast 出去 (对照原版 saveLog 的 onError/成功提示)。
     *
     * @param isRecordLogEnabled 当前是否开启"记录日志" (未开只提醒不中止, 与原版一致)
     */
    fun run(scope: CoroutineScope, isRecordLogEnabled: Boolean) {
        scope.launch {
            val backupPath = backupPathOrNull()
            if (backupPath == null) {
                withContext(mainDispatcher) { Toasters.get().toast("未设置书籍保存位置") }
                return@launch
            }
            if (!isRecordLogEnabled) {
                withContext(mainDispatcher) {
                    Toasters.get().toast("未开启日志记录，请去其他设置里打开记录日志")
                }
                delay(3000)
            }
            runCatching { save(backupPath) }
                .onSuccess {
                    withContext(mainDispatcher) { Toasters.get().toast("已保存至书籍保存位置") }
                }
                .onFailure {
                    // 与原版一致: 失败只记日志 (失败原因可能是授权失效/磁盘满, 具体信息在 AppLog)
                    AppLog.put("保存日志出错\n${it.message}", it)
                }
        }
    }

    /**
     * 打包并写入书籍保存位置下的日志目录 (失败抛异常, 由 [run] 记录; 与原版 `saveLog` 的 onError 一致)。
     *
     * @param backupPath 用户在"书籍保存位置"选定的目录 (空则抛"未设置书籍保存位置", 与原版一致)
     */
    private suspend fun save(backupPath: String) {
        withContext(IoDispatcher) {
            // 运行日志与崩溃日志同在 {filesDir}/log (靠文件名前缀区分), 压一个目录即覆盖两类
            val logDir = File(NativeAppLogStore.logDir)
            if (!logDir.exists()) error("没有可保存的日志文件")
            // 临时 zip 落在应用缓存目录 (打包中转, 与 Android 落 externalCache 同语义)
            val tmpZip = File(AppFilesDirs.get().cacheDir, ZIP_NAME)
            tmpZip.parentFile?.mkdirs()
            if (tmpZip.exists()) tmpZip.delete()
            if (!NativeZipCodec.zipFiles(listOf(logDir.absolutePath), tmpZip.absolutePath)) {
                error("打包日志失败")
            }
            val copied = runCatching {
                copyBackupIntoUserDir(
                    tmpZip.absolutePath,
                    bookSaveSubDir(backupPath, LOG_DIR_NAME),
                    ZIP_NAME,
                )
            }
            tmpZip.delete()
            if (copied.getOrNull() != true) error("写入书籍保存位置失败")
        }
    }
}
