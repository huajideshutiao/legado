package io.legado.app.help

import kotlin.concurrent.Volatile

/**
 * 当前运行版本号抽象 (commonMain)。
 *
 * # 存在原因
 *
 * 启动期默认数据补齐 ([StartupMaintenanceShared.initDefaultData]) 需要"当前版本号"作为
 * 版本推进基准, 但各端取法不同且都依赖平台 API:
 * - Android: `AppConst.appInfo.versionCode` (PackageManager.longVersionCode)
 * - 桌面: `DesktopAppInfo.versionCode` (MANIFEST Implementation-Version 解析)
 * - iOS: `NSBundle.CFBundleShortVersionString` 经 [versionNameToVersionCode] 解析
 * - 鸿蒙: ArkTS 注入的版本名经同一解析
 *
 * 四者都进不了 commonMain, 故按既有 provider 注入模式抽象一层。
 *
 * # 取不到值时返回 0
 *
 * 版本名尚未注入的时机 (鸿蒙 ArkTS 注入与首帧组合存在竞态) 返回 0。调用方据此把
 * "当前版本 0" 视为"版本未推进", 不做任何补齐也不记录, 下次启动再判 —— 与 iOS/鸿蒙
 * 既有 `getAppVersionName()?.let { upVersion(...) }` 的"取不到就下次再判"语义一致。
 *
 * 模式参考 [DefaultDataResourceProvider] / [io.legado.app.help.config.PreferenceProvider]。
 */
interface AppVersionProvider {

    /**
     * 当前运行版本号。
     *
     * 必须每次调用都重新取值 (鸿蒙版本名可能在首次调用后才注入), 实现不要缓存 null 结果。
     */
    val versionCode: Long
}

/**
 * [AppVersionProvider] 容器 (provider 注入模式)。
 *
 * 宿主启动早期注册一次: Android 在 `App.onCreate` 同步段, 桌面在
 * `DesktopCore.registerEarlyProviders`, iOS/鸿蒙在各自入口组合出启动维护协程之前。
 *
 * 未注册时 [get] 抛 [IllegalStateException] 而不是返回 0: 返回 0 会让默认数据补齐永久静默
 * 空转 (版本门恒"未推进"), 属难定位的静默降级。
 */
object AppVersionProviders {

    @Volatile
    private var impl: AppVersionProvider? = null

    /** 宿主启动早期注册一次 (任何启动期维护协程之前)。 */
    fun register(impl: AppVersionProvider) {
        this.impl = impl
    }

    /** 获取已注册实现, 未注册抛出 IllegalStateException。 */
    fun get(): AppVersionProvider =
        impl ?: error("AppVersionProviders not registered; register it in the host entry before startup maintenance")
}

/** 日期码位宽: `MMddHH[mm]` 的定宽位数 (月/日/时/分各 2 位)。 */
private const val dateCodeDigits = 8

/** 版本名各段的位槽: 日期码 8 位 / 年段 2 位 / 主段 2 位。 */
private const val dateCodeSlot = 100_000_000L
private const val yearSlot = dateCodeSlot * 100L
private const val majorSlot = yearSlot * 100L

/**
 * versionName → 单调版本号 (各段按定宽位槽拼接), 供无包信息 API 的端 (iOS/鸿蒙/桌面)
 * 从版本名推导比对基准。
 *
 * 版本名形如 `3.<yy>.<MMddHH>` (CI/测试构建是分钟码 `3.<yy>.<MMddHHmm>`, 见
 * `BuildTimestampValueSource` 与 `.github/workflows/test.yml`), 各段都是零填充定宽数字。
 * 位槽宽度保证低位段 (日期码) 的进位挤不掉高位段 (年段): 数值大小顺序 == 逐段比较的
 * 版本先后 (先主段、再年段、最后日期码), 跨年 (3.26.* → 3.27.*) 与 6/8 位日期码混比
 * 都判得对 —— 6 位日期码右补零到 8 位 (MMddHHmm) 后按同一刻度比较。
 *
 * 各端启动用它判"版本是否变化" (相等则跳过更新日志弹窗), 默认数据补齐
 * ([StartupMaintenanceShared.initDefaultData]) 用它判"版本是否推进" (大小)。
 *
 * 三端 (iOS/鸿蒙/桌面) 写回同一个键 `appVersionCode`, 该键随备份在三端之间走 —— 任一端单独换
 * 编码都会让恢复别端备份后的版本门判错, 改动这里必须三端同批。
 *
 * 段缺失/非数字按 0 计; 日期码取前 8 位参与比较; 第 4 段及以后不参与。
 */
fun versionNameToVersionCode(versionName: String): Long {
    val parts = versionName.split('.')
    val major = parts.getOrNull(0)?.toLongOrNull() ?: 0L
    val yearCode = parts.getOrNull(1)?.toLongOrNull() ?: 0L
    val dateCode = parts.getOrNull(2).orEmpty()
        .padEnd(dateCodeDigits, '0')
        .take(dateCodeDigits)
        .toLongOrNull() ?: 0L
    return major * majorSlot + yearCode * yearSlot + dateCode
}
