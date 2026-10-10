package io.legado.app.help.book

import io.legado.app.constant.PreferKey
import io.legado.app.exception.InvalidBooksDirException
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.appString

/**
 * 有效书籍保存目录: 用户在"其它设置 → 书籍保存位置"选定后写入 [PreferKey.defaultBookTreeUri]
 * 的那个目录; 未设置时为 null (不回退任何平台惯例目录, 保存位置必须由用户显式选定)。
 *
 * 只负责取出"有效目录标识", 不做路径解析: Android 端选过的值是 SAF `content://` 树引用,
 * 由 app 端 FileBookAccessorImpl 自行按 SAF 解析; 桌面/iOS/鸿蒙三端是普通 POSIX 路径,
 * 由各端 FileBookAccessor 自行解析 (与各端 `findBookFileInDir(dirUri, ...)` 的入参同一形态)。
 */
fun effectiveBookSaveDir(): String? =
    PreferenceProviders.get().getString(PreferKey.defaultBookTreeUri)
        .takeIf { it.isNotBlank() }

/**
 * 断言存在有效书籍保存目录并返回 (见 [effectiveBookSaveDir])。
 *
 * 未设置时抛 [InvalidBooksDirException] (本仓库中原版 `NoBooksDirException` 的共用等价类型,
 * 文案取原版同一条资源 `no_books_dir`), 由调用链既有的"去设置目录"出口
 * ([io.legado.app.ui.root.PlatformCapabilities.pickBookTreeUri]) 拉起目录选择器。
 */
fun requireBookSaveDir(): String =
    effectiveBookSaveDir()
        ?: throw InvalidBooksDirException(appString(AppStringKey.no_books_dir))
