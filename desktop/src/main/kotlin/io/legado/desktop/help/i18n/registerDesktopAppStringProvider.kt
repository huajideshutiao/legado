package io.legado.desktop.help.i18n

import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.AppStringProvider
import io.legado.app.help.i18n.registerAppStringProvider
import io.legado.app.ui.compose.platform.jvmGetString

/**
 * 桌面端 [AppStringProvider] 实现: key 名 → Compose Resources 字符串表。
 *
 * [AppStringKey] 常量名与 app 端 R.string 资源名一一对应, 而 CMP 的 `Res.allStringResources`
 * 就是按资源名索引的映射表, 故直接用 [jvmGetString] 查表, 与 app 端
 * `appCtx.getString(resId, *args)` 等价 (含 %s 占位符格式化)。查不到时 jvmGetString 回落 key 名。
 *
 * 注册时机: desktop main 入口早期, 任何 shared commonMain 调用 `appString(...)` 之前。
 * 模式参考 app 端 `registerAndroidAppStringProvider` (AppStringsAndroid.kt)。
 */
private val desktopAppStringProvider = AppStringProvider { key, args ->
    jvmGetString(key.name, *args)
}

/** 桌面端 main 入口早期注册一次。 */
fun registerDesktopAppStringProvider() {
    registerAppStringProvider(desktopAppStringProvider)
}
