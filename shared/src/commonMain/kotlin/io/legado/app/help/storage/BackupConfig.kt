package io.legado.app.help.storage

/**
 * 备份配置 (薄壳, 实现在同包 [BackupConfigShared])。
 *
 * typealias 本身无平台依赖, 已下沉到 commonMain 供各端共用。app 端调用点
 * ([Backup] / [Restore] / [io.legado.app.ui.config.BackupConfigHost] 等) 通过此
 * typealias 零改动访问 [BackupConfigShared] 的成员。
 *
 * Android 专属依赖 (`appCtx.filesDir` / `appCtx.getString(R.string.xxx)`) 在
 * [BackupConfigShared] 中已替换为 provider 注入 ([io.legado.app.help.file.AppFilesDirs] /
 * [io.legado.app.help.i18n.appString]([io.legado.app.help.i18n.AppStringKey])),
 * 行为与原 app 端实现等价 (安卓宿主注册时 [io.legado.app.help.file.AppFilesDirs.get].filesDir
 * = `appCtx.filesDir.path`, [io.legado.app.help.i18n.appString] 走 R.string+appCtx.getString)。
 */
typealias BackupConfig = BackupConfigShared
