package io.legado.desktop.help

import io.legado.app.constant.PreferKey
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.help.toast.Toasters
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 桌面端应用内语言切换。
 *
 * CMP 资源按 `Locale.current` (即 JVM 默认 Locale) 选 `values-xx` 目录, 所以切语言就是
 * 改 JVM 默认 Locale。但已组合出来的字符串不会因此重取 —— CMP 的 ResourceEnvironment
 * 由 `remember(composeLocale, ...)` 缓存, composeLocale 取自 `Locale.current`, 进程内改
 * 默认 Locale 不会触发重组。所以与安卓端一样靠重启进程生效
 * (对照原版 OtherConfigFragment 的 `PreferKey.language -> appCtx.restart()`)。
 *
 * 语言值与安卓端同一套 (composeResources 的 `language_value`): auto/zh/tw/en,
 * 映射逐条对照 app 端 AppContextWrapper.getSetLocale。
 */

/** 进程启动时的系统 Locale (切回 auto 要还原成它, 不能读被改过的 Locale.getDefault)。 */
private val systemLocale: Locale = Locale.getDefault()

/** 把 `PreferKey.language` 应用到 JVM 默认 Locale (启动早期 + 切换后各调一次)。 */
fun applyDesktopLanguagePref() {
    val locale = when (PreferenceProviders.get().getString(PreferKey.language, "auto")) {
        "zh" -> Locale.SIMPLIFIED_CHINESE
        "tw" -> Locale.TRADITIONAL_CHINESE
        "en" -> Locale.ENGLISH
        else -> systemLocale
    }
    Locale.setDefault(locale)
}

/**
 * 切语言后重启应用 (对照原版 appCtx.restart())。
 *
 * 拉不起新进程时不退出当前进程, 只提示用户手动重启 (语言 pref 已写入, 下次启动生效)。
 */
fun restartDesktopAppForLanguage() {
    if (launchRestartProcess()) {
        exitProcess(0)
    }
    Toasters.get().toast("请手动重启应用以应用新语言")
}
