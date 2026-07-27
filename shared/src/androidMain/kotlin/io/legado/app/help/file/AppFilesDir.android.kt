package io.legado.app.help.file

import android.content.Context

/**
 * [AppFilesDir] 的 Android actual 实现。
 *
 * 委托 [Context.filesDir] / [Context.cacheDir] / [Context.getExternalFilesDir],
 * 行为对齐 app 端 `appCtx.filesDir.path` 等访问。
 *
 * # 设计要点
 * - shared androidMain 不依赖 splitties, 通过构造函数接收 [Context]
 * - 路径在构造时确定, 后续不变 (与 app 端 appCtx 行为一致)
 * - [externalFilesDir] 走 `getExternalFilesDir(null)`, 无 SD 卡时返回 null
 * - [externalCacheDir] 走 `externalCacheDir`, 无 SD 卡时返回 null
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
class AndroidAppFilesDir(
    context: Context,
) : AppFilesDir {

    override val filesDir: String = context.filesDir.path

    override val cacheDir: String = context.cacheDir.path

    override val externalFilesDir: String? = context.getExternalFilesDir(null)?.path

    override val externalCacheDir: String? = context.externalCacheDir?.path
}

/**
 * 安卓宿主启动早期注册 [AppFilesDir] 的 actual 实现。
 *
 * 调用时机: App.onCreate, 在任何 commonMain 代码调用 `AppFilesDirs.get()` 之前。
 *
 * @param context 任意 Context (推荐传 `appCtx`), 用于获取 filesDir 等
 *
 * 模式参考 `registerAndroidMediaNotificationProvider`。
 */
fun registerAndroidAppFilesDir(context: Context) {
    AppFilesDirs.register(AndroidAppFilesDir(context.applicationContext))
}
