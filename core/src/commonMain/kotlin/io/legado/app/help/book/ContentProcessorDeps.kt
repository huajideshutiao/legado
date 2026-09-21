package io.legado.app.help.book

import io.legado.app.data.entities.Book

/**
 * ContentProcessor 平台相关依赖抽象 (commonMain)。
 *
 * ContentProcessor 核心正文处理逻辑 (替换规则 / 简繁转换 / 段落重排 / 去重复标题)
 * 下沉 commonMain (见 [ContentProcessorShared]), 但有 3 处依赖平台专属 API 无法跨平台:
 *
 * - `isAndroid8` (Build.VERSION.SDK_INT in 26..27): 安卓 8 特殊处理不间断空格 \u00A0 → 空格
 * - `toastOnUi` (appCtx.toastOnUi): UI Toast 提示, 非 Android 端无 UI toast 能力
 * - `getChapterFiles` (BookHelp.getChapterFiles): 列出已缓存章节文件名,
 *   Android 用 FileUtils + appCtx.externalFiles; 其他端走 [BookStorageProviders] 桥接
 *
 * 由各平台 [ContentProcessorAccessor] 实现注入:
 * - Android: [io.legado.app.model.webBook.WebBookProvidersImpl] 内部 AndroidContentProcessorDeps
 *   包装 Build / appCtx / BookHelp 三处 Android API
 * - Desktop / iOS / 鸿蒙: 复用 [DefaultContentProcessorDeps] (非 Android 端通用实现)
 *
 * 模式参考 [io.legado.app.help.book.BookHelpAccessor] /
 * [io.legado.app.help.config.AppConfigAccessor] 等已有 accessor。
 */
interface ContentProcessorDeps {

    /**
     * 是否 Android 8 (Build.VERSION.SDK_INT in 26..27)。
     *
     * 仅影响 [ContentProcessorShared.getContent] 末尾 `\u00A0 → 空格` 替换分支。
     * 非 Android 端返回 false (与原 desktop/iOS/鸿蒙端无此特殊处理一致)。
     */
    val isAndroid8: Boolean

    /**
     * 显示 UI Toast 提示 (替代 `appCtx.toastOnUi(msg)`)。
     *
     * 仅在替换规则出错时调用 (非主流程), 非 Android 端默认 no-op 即可
     * (用 [DefaultContentProcessorDeps] 默认实现)。
     */
    fun toastOnUi(msg: String)

    /**
     * 列出书籍已缓存章节文件名 (不含路径, 对应 [io.legado.app.help.book.BookHelp.getChapterFiles])。
     *
     * 用于 [ContentProcessorShared.upRemoveSameTitle] 初始化去重标题缓存: 取所有
     * `*.nr` 文件 (表示已禁用去除重复标题的章节) 加入 removeSameTitleCache。
     *
     * 非 Android 端通过 [BookStorageProviders.get].getChapterFiles(book) 取
     * (行为与 app 端 BookHelp.getChapterFiles 一致, 仅文件路径基底不同)。
     */
    fun getChapterFiles(book: Book): Set<String>
}

/**
 * 非 Android 平台 (Desktop / iOS / 鸿蒙) 默认 [ContentProcessorDeps] 实现。
 *
 * - [isAndroid8] = false (非 Android 不做 \u00A0 特殊处理)
 * - [toastOnUi] no-op (非 Android 端无 UI toast, 错误只走 [AppLog])
 * - [getChapterFiles] 委托 [BookStorageProviders.get].getChapterFiles(book)
 *
 * Android 端不使用本类, 直接在 WebBookProvidersImpl 内部实现 [ContentProcessorDeps]
 * 包装 Build / appCtx / BookHelp 三处 Android API。
 */
class DefaultContentProcessorDeps : ContentProcessorDeps {

    override val isAndroid8: Boolean = false

    override fun toastOnUi(msg: String) {
        // 非 Android 端无 UI toast 能力, no-op (错误日志已走 AppLog.put)
    }

    override fun getChapterFiles(book: Book): Set<String> =
        BookStorageProviders.get().getChapterFiles(book).toSet()
}
