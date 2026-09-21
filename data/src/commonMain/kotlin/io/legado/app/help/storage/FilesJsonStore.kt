package io.legado.app.help.storage

import io.legado.app.constant.AppLog
import io.legado.app.help.file.AppFilesDirs

/** filesDir 下 JSON 文件读写 (绕开桌面端 java.util.prefs value ≤ 8192 限制)。 */
object FilesJsonStore {

    fun path(fileName: String): String =
        AppFilesDirs.get().filesDir + BackupFileOps.separator + fileName

    fun readText(fileName: String): String? = runCatching {
        val file = path(fileName)
        if (BackupFileOps.exists(file)) BackupFileOps.readText(file) else null
    }.getOrNull()

    fun writeText(fileName: String, text: String): Boolean = runCatching {
        BackupFileOps.writeText(path(fileName), text)
        true
    }.onFailure {
        AppLog.put("写入配置文件失败: $fileName\n${it.message}", it)
    }.getOrDefault(false)
}
