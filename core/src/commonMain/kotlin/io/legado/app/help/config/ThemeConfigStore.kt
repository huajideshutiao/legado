package io.legado.app.help.config

import io.legado.app.constant.AppLog
import io.legado.app.help.file.AppFilesDirs
import io.legado.app.help.storage.BackupFileOps
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.toJson

/**
 * themeConfig.json 的唯一读写实现 (下沉自 app 端 `ThemeConfig` 的
 * configFilePath / save / getConfigsFromDisk / validateConfig)。
 *
 * app 端 [io.legado.app.help.config.ThemeConfig] 与 [FileThemeConfigProvider] 共用本对象,
 * 保证四端持久化路径/格式完全一致 (备份恢复互读)。
 */
object ThemeConfigStore {

    const val configFileName = "themeConfig.json"

    /** 对照 app 端 `FileUtils.getPath(appCtx.filesDir, configFileName)` */
    val configFilePath: String
        get() = AppFilesDirs.get().filesDir + BackupFileOps.separator + configFileName

    /** 对照 app 端 getConfigsFromDisk: 文件不存在/解析失败返回空列表 */
    fun load(): List<ThemeConfigData> = runCatching {
        if (BackupFileOps.exists(configFilePath)) {
            GSON.fromJsonArray<ThemeConfigData>(BackupFileOps.readText(configFilePath)).getOrThrow()
        } else {
            emptyList()
        }
    }.getOrElse { e ->
        AppLog.putDebug("读取 themeConfig.json 出错\n${e.message}", e)
        emptyList()
    }

    /** 对照 app 端 save: 先删后建再覆盖写 */
    fun save(configs: List<ThemeConfigData>) {
        runCatching {
            BackupFileOps.delete(configFilePath)
            BackupFileOps.writeText(configFilePath, GSON.toJson(configs))
        }.onFailure { e ->
            AppLog.put("保存 themeConfig.json 出错\n${e.message}", e)
        }
    }

    /** 对照 app 端 validateConfig: 4 个色值可解析才有效 */
    fun validate(config: ThemeConfigData): Boolean = runCatching {
        ColorUtils.parseColor(config.primaryColor)
        ColorUtils.parseColor(config.accentColor)
        ColorUtils.parseColor(config.backgroundColor)
        ColorUtils.parseColor(config.bottomBackground)
    }.isSuccess
}
