package io.legado.app.help.book

import io.legado.app.constant.PreferKey
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.appString
import io.legado.app.help.storage.DataStorageProviders

/**
 * 有效书籍保存目录 (与备份路径 [io.legado.app.help.storage.DataStorage.defaultBackupDir]
 * 同一套"默认目录 + 可为 null + 三分支"语义, 见 [io.legado.app.ui.route.BackupConfigRoute]
 * 调用方):
 *
 * 1. 用户已在"其它设置 → 书籍保存位置"选过 ([PreferKey.defaultBookTreeUri]) → 用选定目录;
 * 2. 未选过但平台有惯例默认目录 ([io.legado.app.help.storage.DataStorage.defaultBookDir]) →
 *    用默认目录, 不写 pref、不打扰用户 (设置项仍显示"未设置");
 * 3. 都没有 (Android: 保存位置须经 SAF 授权, 默认目录为 null) → 返回 null,
 *    调用方应改为给出到"选择书籍目录"的用户出口。
 *
 * 只负责取出"有效目录标识", 不做路径解析: Android 端选过的值是 SAF `content://` 树引用,
 * 由 app 端 FileBookAccessorImpl 自行按 SAF 解析; 桌面/iOS/鸿蒙三端是普通 POSIX 路径,
 * 由各端 FileBookAccessor 自行解析 (与各端 `findBookFileInDir(dirUri, ...)` 的入参同一形态)。
 */
fun effectiveBookSaveDir(): String? =
    PreferenceProviders.get().getString(PreferKey.defaultBookTreeUri)
        .takeIf { it.isNotBlank() }
        ?: DataStorageProviders.getOrNull()?.defaultBookDir

/**
 * 断言存在有效书籍保存目录并返回 (见 [effectiveBookSaveDir])。
 *
 * 都没有时抛 [InvalidBooksDirException] (本仓库中原版 `NoBooksDirException` 的共用等价类型,
 * 文案取原版同一条资源 `no_books_dir`), 由调用链既有的"去设置目录"出口
 * ([io.legado.app.ui.root.PlatformCapabilities.pickBookTreeUri]) 拉起目录选择器。
 * Android 端未选保存位置时即走此出口 (保持原版行为); 桌面/iOS/鸿蒙三端有默认目录, 不会走到这。
 */
fun requireBookSaveDir(): String =
    effectiveBookSaveDir()
        ?: throw InvalidBooksDirException(appString(AppStringKey.no_books_dir))
