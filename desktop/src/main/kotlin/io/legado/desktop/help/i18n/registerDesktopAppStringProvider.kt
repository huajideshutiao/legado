package io.legado.desktop.help.i18n

import io.legado.app.help.i18n.AppStringKey
import io.legado.app.help.i18n.AppStringProvider
import io.legado.app.help.i18n.registerAppStringProvider

/**
 * 桌面端 [AppStringProvider] 实现: 返回 key 名作为 fallback。
 *
 * app 端通过 R.string + appCtx.getString 映射 key 到本地化文案 (strings.xml 多语言资产);
 * 桌面端无 Android resources 系统, 也未配置 ResourceBundle (desktop/src/main/resources
 * 目前仅含 icon.png), 简化实现直接返回 `key.name` (枚举常量名) 作为兜底文案。
 *
 * 与未注册时的 fallback 行为一致 (见 shared `appString` 函数 `?: key.name`),
 * 但语义上"已注册", 后续若桌面端引入 strings_zh.properties / ResourceBundle 扩展,
 * 仅需替换本 impl 即可, 调用方零改动。
 *
 * 注册时机: desktop main 入口早期, 任何 shared commonMain 调用 `appString(...)` 之前。
 * 模式参考 app 端 `registerAndroidAppStringProvider` (AppStringsAndroid.kt)。
 */
private val desktopAppStringProvider = AppStringProvider { key, args ->
    // 无参直接返回 key 名; 有参时拼接 args 避免 %s 占位符泄漏到 UI
    if (args.isEmpty()) {
        key.name
    } else {
        // 简化处理: key 名 + args 拼接, 不做 %s 格式化 (桌面端文案兜底, 不追求精确)
        buildString {
            append(key.name)
            args.forEach { arg ->
                append(' ')
                append(arg?.toString() ?: "null")
            }
        }
    }
}

/** 桌面端 main 入口早期注册一次。 */
fun registerDesktopAppStringProvider() {
    registerAppStringProvider(desktopAppStringProvider)
}
