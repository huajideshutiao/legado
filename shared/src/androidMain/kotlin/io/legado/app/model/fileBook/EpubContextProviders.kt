package io.legado.app.model.fileBook

import android.content.Context

/**
 * EpubFile androidMain 专用的 ApplicationContext holder。
 *
 * # 背景
 * [LocalEpubResource] 的 android actual 需要用 `Context.contentResolver.openFileDescriptor`
 * 打开 content scheme 的本地书籍 (原 app 端 `BookHelp.getBookPFD` 行为), shared androidMain
 * 不依赖 splitties.init.appCtx, 需宿主启动早期注入 [Context]。
 *
 * # 注册时机
 * app 端 `App.onCreate` 中调用 [registerEpubApplicationContext], 与
 * `registerSharedAppContext` / `registerAndroidToaster` 等同批, 在任何 EpubFile
 * 调用之前。未注册时 content scheme 路径返回 null (PFD 获取失败, EpubFile 记录错误日志)。
 *
 * # 模式参考
 * `io.legado.app.ui.platform.registerSharedAppContext` (StringRes.android.kt)。
 */
@Volatile
internal var epubApplicationContext: Context? = null

/**
 * 安卓宿主启动早期注册 ApplicationContext, 供 [LocalEpubResource] 的 android actual
 * 打开 content scheme 本地书籍。
 *
 * @param ctx 任意 Context (推荐传 `appCtx`), 内部只用其 applicationContext
 */
fun registerEpubApplicationContext(ctx: Context) {
    epubApplicationContext = ctx.applicationContext
}
