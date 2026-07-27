package io.legado.app.help

import io.legado.app.constant.AppLog
import io.legado.app.data.AppDbProviders
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.utils.GSON
import io.legado.app.utils.KS_JSON
import io.legado.app.utils.fromJsonArray

/**
 * 默认数据加载核心 (shared commonMain 下沉版)。
 *
 * # 背景
 *
 * 对照 app 端原 `io.legado.app.help.DefaultData` object:
 * - `httpTTS` / `txtTocRules` / `dictRules` / `keyboardAssists` 四个 lazy 属性仅依赖
 *   assets 读取 (JSON 字符串) + JSON 反序列化 + shared 实体类 (HttpTTS/TxtTocRule/
 *   DictRule/KeyboardAssist 均在 commonMain), 可以下沉。
 * - `importDefaultHttpTTS` / `importDefaultTocRules` / `importDefaultDictRules` 三个方法
 *   仅依赖 DAO (httpTTSDao/txtTocRuleDao/dictRuleDao, 均 suspend), 通过 [AppDbProviders]
 *   间接访问, 可以下沉 (已改为 suspend, 原 runBlocking 移除)。
 *
 * # 不下沉部分 (app 端特有)
 *
 * - `readConfigs: List<ReadBookConfig.Config>`: 依赖 app 端 [io.legado.app.help.config.ReadBookConfig.Config]
 *   data class (含 Drawable 等 Android 类型), 留 app 端 [io.legado.app.help.DefaultData]。
 * - `upVersion()`: 依赖 app 端 [io.legado.app.help.config.LocalConfig] (SharedPreferences)
 *   + [io.legado.app.constant.AppConst.appInfo] (PackageManager), 留 app 端。
 *
 * # 资源读取
 *
 * 通过 [DefaultDataResourceProvider] 接口抽象, 单一数据源在
 * `commonMain/composeResources/files/defaultData/`, 各端实现见接口 KDoc。
 *
 * # 容错行为保持
 *
 * - `httpTTS`: `KS_JSON.decodeFromString` 失败返回 `emptyList()` (与原 `runCatching ... getOrElse`)
 * - `txtTocRules`: `GSON.fromJsonArray` 失败记 AppLog 后返回 `emptyList()`
 * - `dictRules`: `GSON.fromJsonArray` 失败抛异常 (与原 `getOrThrow()`)
 * - `keyboardAssists`: `GSON.fromJsonArray` 失败抛异常 (与原 `getOrThrow()`)
 *
 * 模式参考 [io.legado.app.help.source.SourceHelp] (shared 下沉 + app 薄壳)。
 */
object DefaultDataShared {

    /** 默认 HttpTTS 列表 (读 httpTTS.json, 失败返回空列表)。 */
    val httpTTS: List<HttpTTS> by lazy {
        val json = DefaultDataResourceProviders.get().readResource("httpTTS.json")
        runCatching {
            KS_JSON.decodeFromString<List<HttpTTS>>(json)
        }.getOrElse {
            emptyList()
        }
    }

    /** 默认 TxtToc 规则列表 (读 txtTocRule.json, 失败记日志返回空列表)。 */
    val txtTocRules: List<TxtTocRule> by lazy {
        val json = DefaultDataResourceProviders.get().readResource("txtTocRule.json")
        GSON.fromJsonArray<TxtTocRule>(json)
            .onFailure { AppLog.put("解析默认 txtTocRule.json 失败", it) }
            .getOrNull() ?: emptyList()
    }

    /** 默认字典规则列表 (读 dictRules.json, 失败抛异常, 与原 getOrThrow 一致)。 */
    val dictRules: List<DictRule> by lazy {
        val json = DefaultDataResourceProviders.get().readResource("dictRules.json")
        GSON.fromJsonArray<DictRule>(json).getOrThrow()
    }

    /** 默认键盘助手列表 (读 keyboardAssists.json, 失败抛异常, 与原 getOrThrow 一致)。 */
    val keyboardAssists: List<KeyboardAssist> by lazy {
        val json = DefaultDataResourceProviders.get().readResource("keyboardAssists.json")
        GSON.fromJsonArray<KeyboardAssist>(json).getOrThrow()
    }

    /**
     * 导入默认 HttpTTS (对应原 `DefaultData.importDefaultHttpTTS()`)。
     *
     * 行为与原完全一致: `appDb.httpTTSDao.deleteDefault();
     * appDb.httpTTSDao.insert(*httpTTS.toTypedArray())`。
     * 原 runBlocking 已移除 (commonMain 禁用, Native 死锁风险), 改为 suspend。
     */
    suspend fun importDefaultHttpTTS() {
        val appDb = AppDbProviders.get()
        appDb.httpTTSDao.deleteDefault()
        appDb.httpTTSDao.insert(*httpTTS.toTypedArray())
    }

    /**
     * 导入默认 TxtToc 规则 (对应原 `DefaultData.importDefaultTocRules()`)。
     *
     * 行为与原完全一致: `appDb.txtTocRuleDao.deleteDefault();
     * appDb.txtTocRuleDao.insert(*txtTocRules.toTypedArray())`。
     * 原 runBlocking 已移除 (commonMain 禁用, Native 死锁风险), 改为 suspend。
     */
    suspend fun importDefaultTocRules() {
        val appDb = AppDbProviders.get()
        appDb.txtTocRuleDao.deleteDefault()
        appDb.txtTocRuleDao.insert(*txtTocRules.toTypedArray())
    }

    /**
     * 导入默认字典规则 (对应原 `DefaultData.importDefaultDictRules()`)。
     *
     * 行为与原完全一致: `appDb.dictRuleDao.insert(*dictRules.toTypedArray())`。
     * 原 runBlocking 已移除 (commonMain 禁用, Native 死锁风险), 改为 suspend。
     */
    suspend fun importDefaultDictRules() {
        val appDb = AppDbProviders.get()
        appDb.dictRuleDao.insert(*dictRules.toTypedArray())
    }
}
