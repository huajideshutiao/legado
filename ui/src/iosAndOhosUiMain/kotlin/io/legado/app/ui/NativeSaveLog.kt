package io.legado.app.ui

import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.log.NativeAppLogStore
import io.legado.app.help.storage.NativeZipCodec
import io.legado.app.help.storage.copyBackupIntoUserDir
import io.legado.app.utils.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * iOS/鸿蒙"保存日志"的打包逻辑 (对照原版 `AboutFragment.copyLogs`)。
 *
 * 原版逐字行为: 把 `{externalCache}/logs`(运行日志) + `{externalCache}/crash`(崩溃日志) +
 * `logcat.txt` 压成 `logs.zip` 写进**用户设置的备份目录**, 成功 toast「已保存至备份目录」。
 *
 * 与 Android 的差别只有两处, 都是平台事实而非设计选择:
 * - 目录不同: 本端运行日志与崩溃日志同处 `{filesDir}/logs` (崩溃在其 `crash/` 子目录),
 *   而非 `externalCache` 下两个平级目录; 故只压 `logs` 一个源即可覆盖两类
 * - 没有 logcat (iOS/鸿蒙不提供该命令), 原版那一路无对应物, 不伪造空文件
 *
 * 写进用户选定目录走 [copyBackupIntoUserDir] —— 与备份 zip 同一条授权通道
 * (iOS security-scoped 租约 / 鸿蒙持久授权激活), 未登记授权的目录会明确报失败。
 */
internal object NativeSaveLog {

    private const val ZIP_NAME = "logs.zip"

    /**
     * 打包并写入备份目录 (失败抛异常, 由调用方 toast; 与原版 `saveLog` 的 onError 一致)。
     *
     * @param backupPath 用户在备份设置里选定的目录 (空则抛"未设置备份目录", 与原版一致)
     */
    suspend fun save(backupPath: String?) {
        withContext(Dispatchers.IO) {
            if (backupPath.isNullOrBlank()) error("未设置备份目录")
            // 运行日志与崩溃日志同在 {filesDir}/logs (崩溃在 crash/ 子目录), 压一个源即覆盖两类
            val logDir = File(NativeAppLogStore.logDir)
            if (!logDir.exists()) error("没有可保存的日志文件")
            // 临时 zip 落在应用缓存目录 (打包中转, 与 Android 落 externalCache 同语义)
            val tmpZip = File(AppFilesDirs.get().cacheDir, ZIP_NAME).apply { parentFile?.mkdirs() }
            if (tmpZip.exists()) tmpZip.delete()
            if (!NativeZipCodec.zipFiles(listOf(logDir.absolutePath), tmpZip.absolutePath)) {
                error("打包日志失败")
            }
            val copied = runCatching {
                copyBackupIntoUserDir(tmpZip.absolutePath, backupPath, ZIP_NAME)
            }
            tmpZip.delete()
            if (copied.getOrNull() != true) error("写入备份目录失败")
        }
    }
}

/** 备份目录取值 (与备份设置页写的是同一个 key, 见 `BackupConfigRoute`)。 */
internal fun backupPathOrNull(): String? =
    PreferenceProviders.get().getStringOrNull(PreferKey.backupPath)?.takeIf { it.isNotBlank() }
