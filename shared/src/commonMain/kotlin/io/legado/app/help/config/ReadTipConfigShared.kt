package io.legado.app.help.config

/**
 * `ReadTipConfig` 的 KMP 下沉版本（桌面/iOS/鸿蒙共用）。
 *
 * 与 app 端 `io.legado.app.help.config.ReadTipConfig` 的差异：
 * 1. 用 `class` + 委托 [ReadBookConfigShared] 取代 Android `object` + `ReadBookConfig.config` 直读。
 *    app 端 `tipHeaderLeft` 等槽位实际存在 `ReadBookConfig.Config` data class 里
 *    （最终落到 readConfig.json）；桌面端 `ReadBookConfigShared` 已把这些字段简化为走 prefs，
 *    本类直接转发到 [readBookConfig] 的对应属性，避免重复存储。
 * 2. **常量值** ([none] / [chapterTitle] / [time] / [battery] / [pageAndTotal] 等)
 *    与 app 端 `ReadTipConfig` 完全一致，跨平台共用。
 * 3. **资源相关** ([tipNames] / [tipColorNames] / [tipDividerColorNames] /
 *    `getHeaderModes(context)` / `getFooterModes(context)`) 不下沉，
 *    留 app 端 actual 用 `appCtx.resources` 实现，桌面端如需展示由各平台 Composable 自取。
 *
 * 字段命名与 app 端保持一致，方便后续 app 端迁移到 Shared 版时最小改动。
 */
@Suppress("MemberVisibilityCanBePrivate")
class ReadTipConfigShared(private val readBookConfig: ReadBookConfigShared) {

    /** 页眉左槽位 tip 类型。 */
    var tipHeaderLeft: Int
        get() = readBookConfig.tipHeaderLeft
        set(value) {
            readBookConfig.tipHeaderLeft = value
        }

    /** 页眉中槽位 tip 类型。 */
    var tipHeaderMiddle: Int
        get() = readBookConfig.tipHeaderMiddle
        set(value) {
            readBookConfig.tipHeaderMiddle = value
        }

    /** 页眉右槽位 tip 类型。 */
    var tipHeaderRight: Int
        get() = readBookConfig.tipHeaderRight
        set(value) {
            readBookConfig.tipHeaderRight = value
        }

    /** 页脚左槽位 tip 类型。 */
    var tipFooterLeft: Int
        get() = readBookConfig.tipFooterLeft
        set(value) {
            readBookConfig.tipFooterLeft = value
        }

    /** 页脚中槽位 tip 类型。 */
    var tipFooterMiddle: Int
        get() = readBookConfig.tipFooterMiddle
        set(value) {
            readBookConfig.tipFooterMiddle = value
        }

    /** 页脚右槽位 tip 类型。 */
    var tipFooterRight: Int
        get() = readBookConfig.tipFooterRight
        set(value) {
            readBookConfig.tipFooterRight = value
        }

    /** 页眉显示模式（0:状态栏显示时隐藏 1:显示 2:隐藏）。 */
    var headerMode: Int
        get() = readBookConfig.headerMode
        set(value) {
            readBookConfig.headerMode = value
        }

    /** 页脚显示模式（0:显示 1:隐藏）。 */
    var footerMode: Int
        get() = readBookConfig.footerMode
        set(value) {
            readBookConfig.footerMode = value
        }

    /** tip 颜色索引。 */
    var tipColor: Int
        get() = readBookConfig.tipColor
        set(value) {
            readBookConfig.tipColor = value
        }

    /** tip 分隔线颜色索引（-1 表示不显示）。 */
    var tipDividerColor: Int
        get() = readBookConfig.tipDividerColor
        set(value) {
            readBookConfig.tipDividerColor = value
        }

    companion object {
        // tip 类型常量，值与 app 端 ReadTipConfig 完全一致
        const val none = 0
        const val chapterTitle = 1
        const val time = 2
        const val battery = 3
        const val batteryPercentage = 10
        const val page = 4
        const val totalProgress = 5
        const val pageAndTotal = 6
        const val bookName = 7
        const val timeBattery = 8
        const val timeBatteryPercentage = 9
        const val totalProgress1 = 11

        /**
         * 所有 tip 类型值（与 app 端 `tipValues` 一致），供 UI 渲染列表用。
         * 资源字符串展示由各平台 actual 自行映射。
         */
        val tipValues: IntArray = intArrayOf(
            none, bookName, chapterTitle, time, battery, batteryPercentage, page,
            totalProgress, totalProgress1, pageAndTotal, timeBattery, timeBatteryPercentage
        )
    }
}
