package io.legado.app.help

import io.legado.app.constant.AppConst
import io.legado.app.constant.appInfo
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.printOnDebug
import splitties.init.appCtx
import java.io.File

/**
 * 默认数据加载入口 (app 端薄壳)。
 *
 * # 下沉说明
 *
 * 核心逻辑 (httpTTS/txtTocRules/dictRules/keyboardAssists 属性 + importDefaultHttpTTS/
 * importDefaultTocRules/importDefaultDictRules 方法) 已下沉到 shared commonMain [DefaultDataShared]。
 * 本 object 仅保留 app 端特有部分:
 *
 * - `readConfigs`: 依赖 app 端 [ReadBookConfig.Config] data class (含 Drawable 等 Android 类型),
 *   无法下沉 commonMain。
 * - `upVersion()`: 依赖 app 端 [LocalConfig] (SharedPreferences) + [AppConst.appInfo]
 *   (PackageManager), 无法下沉 commonMain。
 *
 * # 资源读取
 *
 * [DefaultDataShared] 通过 [DefaultDataResourceProvider] 接口读取资源, app 端在
 * `App.onCreate` 早期注册实现 (用 `appCtx.assets.open` 读取 app/src/main/assets/defaultData/),
 * 行为与原 DefaultData 完全一致。
 *
 * 模式参考 [io.legado.app.help.source.SourceHelp] (shared 下沉 + app 薄壳)。
 */
object DefaultData {

    fun upVersion() {
        if (LocalConfig.versionCode < AppConst.appInfo.versionCode) {
            Coroutine.async {
                if (LocalConfig.needUpHttpTTS) {
                    DefaultDataShared.importDefaultHttpTTS()
                }
                if (LocalConfig.needUpTxtTocRule) {
                    DefaultDataShared.importDefaultTocRules()
                }
                if (LocalConfig.needUpDictRule) {
                    DefaultDataShared.importDefaultDictRules()
                }
            }.onError {
                it.printOnDebug()
            }
        }
    }

    /**
     * 默认阅读配置列表 (读 readConfig.json)。
     *
     * 依赖 app 端 [ReadBookConfig.Config] data class, 未下沉 commonMain。
     * 资源读取仍走 `appCtx.assets.open` (app 端 assets), 不经 DefaultDataResourceProvider。
     */
    val readConfigs: List<ReadBookConfig.Config> by lazy {
        val json = String(
            appCtx.assets.open("defaultData${File.separator}${ReadBookConfig.configFileName}")
                .readBytes()
        )
        GSON.fromJsonArray<ReadBookConfig.Config>(json).getOrNull()
            ?: emptyList()
    }

    /** 默认 HttpTTS 列表 (委托 [DefaultDataShared])。 */
    val httpTTS: List<HttpTTS>
        get() = DefaultDataShared.httpTTS

    /** 默认 TxtToc 规则列表 (委托 [DefaultDataShared])。 */
    val txtTocRules: List<TxtTocRule>
        get() = DefaultDataShared.txtTocRules

    /** 默认字典规则列表 (委托 [DefaultDataShared])。 */
    val dictRules: List<DictRule>
        get() = DefaultDataShared.dictRules

    /** 默认键盘助手列表 (委托 [DefaultDataShared])。 */
    val keyboardAssists: List<KeyboardAssist>
        get() = DefaultDataShared.keyboardAssists

    /** 导入默认 HttpTTS (委托 [DefaultDataShared])。 */
    suspend fun importDefaultHttpTTS() = DefaultDataShared.importDefaultHttpTTS()

    /** 导入默认 TxtToc 规则 (委托 [DefaultDataShared])。 */
    suspend fun importDefaultTocRules() = DefaultDataShared.importDefaultTocRules()

    /** 导入默认字典规则 (委托 [DefaultDataShared])。 */
    suspend fun importDefaultDictRules() = DefaultDataShared.importDefaultDictRules()

}
