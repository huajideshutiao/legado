package io.legado.app.help

import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadConfigDefaults
import io.legado.app.help.config.ReadStyleConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.utils.printOnDebug
import io.legado.app.utils.putInt

/**
 * 默认数据加载入口 (app 端薄壳)。
 *
 * # 下沉说明
 *
 * 核心逻辑 (httpTTS/txtTocRules/dictRules/keyboardAssists 属性 + importDefaultHttpTTS/
 * importDefaultTocRules/importDefaultDictRules 方法) 已下沉到 shared commonMain [DefaultDataShared]。
 * 本 object 仅保留 app 端特有部分:
 *
 * - `readConfigs`: 委托 shared 的 [ReadConfigDefaults] (readConfig.json 已内联下沉),
 *   仅保留转发。
 * - `upVersion()`: 编排已下沉到 shared commonMain [StartupMaintenanceShared.initDefaultData],
 *   本函数只剩"注入 app 端 `local` 配置存储 + 起协程"两件事。之所以还要这层薄壳而不是让调用方
 *   直接调下沉件: app 端的版本标记 (`appVersionCode` / `httpTtsVersion` / `txtTocRuleVersion` /
 *   `needUpDictRule`) 历史上存在**独立的 `local` 配置**里 (而 `:data` 看不到 `:core` 的
 *   `LocalConfigStore`), 且启动期只需要一个入口 (保持原版 `App.onCreate` 的调用位置与时机)。
 *
 * # 资源读取
 *
 * [DefaultDataShared] 通过 [DefaultDataResourceProvider] 接口读取资源, app 端在
 * `App.onCreate` 早期注册实现 (:ui 的 ComposeResourceDefaultDataProvider,
 * 经 composeResources 生成的 Res 取数)。
 *
 * 模式参考 [io.legado.app.help.source.SourceHelp] (shared 下沉 + app 薄壳)。
 */
object DefaultData {

    /**
     * 首启 / 升级时的默认数据补齐 (转发下沉件 [StartupMaintenanceShared.initDefaultData])。
     *
     * 语义对齐原版 `DefaultData.upVersion()`:
     * - 三道判断整包在 `LocalConfig.versionCode < AppConst.appInfo.versionCode` 这道版本门内;
     * - 资源版本 key 的读写走 `LocalConfig.needUpXxx` → [io.legado.app.help.config.LocalConfigShared.isLastVersion],
     *   **先写回再返回"需要导入"** —— 每资源每版本只尝试一次, 那次抛异常也不重试 (原版由
     *   `Coroutine.async { ... }.onError { it.printOnDebug() }` 记日志后放过);
     * - `appVersionCode` 本函数只读不写 (写回它的只有 `MainActivity.upVersion()` 的弹窗)。
     */
    fun upVersion() {
        Coroutine.async {
            StartupMaintenanceShared.initDefaultData(
                getInt = { key, defValue -> LocalConfig.getInt(key, defValue) },
                putInt = { key, value -> LocalConfig.putInt(key, value) },
                getBoolean = { key, defValue -> LocalConfig.getBoolean(key, defValue) },
                getLong = { key, defValue -> LocalConfig.getLong(key, defValue) },
            )
        }.onError {
            it.printOnDebug()
        }
    }

    /**
     * 默认阅读配置列表 (委托 [ReadConfigDefaults], 同一份 readConfig.json 已内联下沉)。
     */
    val readConfigs: List<ReadStyleConfig>
        get() = ReadConfigDefaults.readConfigs

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
