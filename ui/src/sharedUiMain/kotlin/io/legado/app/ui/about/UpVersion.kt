package io.legado.app.ui.about

import io.legado.app.help.config.LocalConfigShared
import io.legado.app.help.config.PreferenceProviders
import io.legado.app.ui.root.AppNavigatorProviders
import io.legado.app.ui.root.AppOverlay
import kotlinx.coroutines.flow.first

/** 升级更新日志文档名 (composeResources files/web/help/md/updateLog.md, 不含后缀)。 */
private const val updateLogDocName = "updateLog"

/** 保存的版本号存储 key (与 app 端 LocalConfig 的 versionCodeKey 同名)。 */
private const val savedVersionCodeKey = "appVersionCode"

/**
 * 版本更新弹窗的存储面 (对照 app 端 LocalConfig.versionCode/isFirstOpenApp 语义)。
 *
 * app 端实现包装 LocalConfig (versionCode 同时供 DefaultData.upVersion 做默认数据
 * 版本推进判断, 必须写回同一处); 桌面/iOS/鸿蒙用 [preferenceUpVersionStore]。
 */
interface UpVersionStore {
    fun getSavedVersionCode(): Long

    fun saveVersionCode(versionCode: Long)

    /** 读取并消费首次打开标志 (读即置 false, 算法见 [LocalConfigShared.isFirstOpen])。 */
    fun consumeFirstOpen(): Boolean
}

/** [UpVersionStore] 的 PreferenceProviders 实现 (桌面/iOS/鸿蒙共用)。 */
fun preferenceUpVersionStore(): UpVersionStore = object : UpVersionStore {
    private val prefs get() = PreferenceProviders.get()

    override fun getSavedVersionCode(): Long = prefs.getLong(savedVersionCodeKey, 0L)

    override fun saveVersionCode(versionCode: Long) {
        prefs.putLong(savedVersionCodeKey, versionCode)
    }

    override fun consumeFirstOpen(): Boolean = LocalConfigShared.isFirstOpen(
        getBoolean = prefs::getBoolean,
        putBoolean = prefs::putBoolean,
    )
}

/**
 * 启动版本更新弹窗 (对照原版 MainActivity.upVersion, 四端共用)。
 *
 * 版本号变化时: 首次安装弹帮助 (appHelp, key="help"), 升级安装弹更新日志
 * (updateLog, key="updateLog", 标题"更新日志")。DEBUG 构建跳过更新日志
 * (对照原版 !BuildConfig.DEBUG 门; iOS/鸿蒙无构建类型感知, 传 false)。
 * 弹窗挂起等待关闭后返回, 保持原版启动序列的串行语义。
 *
 * @param currentVersionCode 当前运行版本号 (app=AppConst.appInfo.versionCode,
 *   desktop=DesktopAppInfo.versionCode, iOS/鸿蒙=versionNameToVersionCode 解析)
 * @param isDebug 当前是否 DEBUG 构建
 * @param store 版本号/首次打开标志存储
 */
suspend fun upVersion(
    currentVersionCode: Long,
    isDebug: Boolean,
    store: UpVersionStore,
) {
    if (store.getSavedVersionCode() == currentVersionCode) return
    store.saveVersionCode(currentVersionCode)
    val navigator = AppNavigatorProviders.awaitNavigator()
    if (store.consumeFirstOpen()) {
        // 首次安装: 弹帮助 (对照原版 appHelp 分支)
        navigator.showOverlay(AppOverlay.Dialog(key = "help", payload = "appHelp"))
        navigator.overlays.first { list ->
            list.none { it.key == "help" }
        }
    } else if (!isDebug) {
        // 升级安装: 弹更新日志 (对照原版 updateLog 分支)
        navigator.showOverlay(AppOverlay.Dialog(key = updateLogDocName))
        navigator.overlays.first { list ->
            list.none { it.key == updateLogDocName }
        }
    }
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
 * [upVersion] 用它判"版本是否变化" (相等性), 启动维护
 * ([io.legado.app.help.StartupMaintenanceShared.initDefaultData]) 用它判"版本是否推进" (大小)。
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
