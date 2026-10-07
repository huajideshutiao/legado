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

/**
 * versionName → 数字版本号 (major*10000 + minor*100 + patch, 与桌面端
 * DesktopAppInfo.versionCode 同一算法), 供无包信息 API 的端 (iOS/鸿蒙) 从
 * 版本名推导比对基准; 段缺失/非数字按 0 计, 仅作"版本是否变化"的指纹。
 */
fun versionNameToVersionCode(versionName: String): Long {
    val parts = versionName.split('.')
    return (parts.getOrNull(0)?.toLongOrNull() ?: 0L) * 10000L +
        (parts.getOrNull(1)?.toLongOrNull() ?: 0L) * 100L +
        (parts.getOrNull(2)?.toLongOrNull() ?: 0L)
}
