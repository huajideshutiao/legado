package io.legado.desktop.js

import io.legado.app.help.file.desktopAppRootDir
import io.legado.app.model.SharedJsScope
import io.legado.app.model.script.JsBindingInjector
import io.legado.app.model.script.JsEngineType
import io.legado.app.model.script.JsEngines
import io.legado.app.model.script.quickjs.QuickJsJsEngine
import io.legado.app.utils.ChineseUtils
import io.legado.app.utils.TcDictCachePathProvider
import io.legado.desktop.image.DesktopImageOps
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths

/**
 * 桌面端 JS 引擎注册入口 (KP1.1)。
 *
 * 在 desktop Main 启动早期调用一次, 完成 4 件事:
 * 1. 注册 [DesktopImageOps] 到 [JsBindingInjector]
 *    (JsBindings 构造时访问 `JsBindingInjector.image` getter, 未注册会 checkNotNull 失败,
 *    任何 `JsEngine.eval` 都跑不了);
 * 2. 注册 [QuickJsJsEngine] 到 [JsEngines] 作为 QUICKJS 引擎实现
 *    (QuickJsJsEngine 已在 KP1.1 下沉到 `modules/shared/src/jvmAndAndroidMain`,
 *    委托 `modules:quickjs` KMP 化后的 commonMain QuickJsEngine API,
 *    Android 端 `JsEnginesAndroid.kt` 也注册同一个 object, 行为完全一致);
 * 3. 注册 [DesktopQuickJsSharedJsScopeProvider] 到 [SharedJsScope]
 *    (jsLib 共享 scope 缓存, 三层结构 bytecodeCache + ThreadLocal LRU + versionSeq,
 *    与 app 端 [io.legado.app.model.script.quickjs.QuickJsSharedJsScopeProvider] 行为一致,
 *    仅 jsLib URL 下载内容缓存从 ACache 改为 in-memory Map);
 * 4. 注册 [ChineseUtils.pathProvider] 为 [DesktopTcDictCachePathProvider]
 *    (简繁词典缓存文件定位器, 指向 `~/.legado/cache/tc_cache/` 目录;
 *    桌面端不实现后台拉取, 文件不存在时 ChineseUtils.loadDict 会 fallback 到
 *    quick-transfer 自带默认词典, 行为可用但不持久化)。
 *
 * # 与 Android 端 `registerAndroidJsEngines` 的差异
 * - Android 端注册 BitmapImageOps, 桌面端注册 [DesktopImageOps] (java.awt.image.BufferedImage);
 * - Android 端 RuleBigDataProviders/SourceCacheProviders/SourceNetworkProviders 等业务 provider
 *   在 registerAndroidJsEngines 内注册; 桌面端移到 [io.legado.desktop.help.source.registerDesktopSourceProviders]
 *   统一注册 (业务 provider 与 JS 引擎注册解耦, 便于维护);
 * - Android 端调用 registerAndroidChineseUtils 注册 TcDictCachePathProvider (含后台拉取副作用);
 *   桌面端在 registerDesktopJsEngines 内直接注册简化版 (无后台拉取)。
 *
 * # native 库依赖
 * `QuickJsJsEngine.eval` 内部调 `QuickJsEngine.eval` -> JNI -> native legado_quickjs 库,
 * 桌面端首次访问时触发 `loadLegadoQuickJsNative()` 加载 .dll/.so/.dylib,
 * 详见 `modules/quickjs/src/jvmMain/kotlin/com/script/quickjs/Platform.kt` 注释。
 * 若 native 库未构建, eval 会抛 UnsatisfiedLinkError, 调用方需先执行
 * `./gradlew :modules:quickjs:buildJvmNativeLib`。
 */
fun registerDesktopJsEngines() {
    JsBindingInjector.registerImageOps(DesktopImageOps)
    JsEngines.registerProvider { type ->
        when (type) {
            JsEngineType.QUICKJS -> QuickJsJsEngine
            else -> error("rhino 已弃用,JsEngines.type 固定 QUICKJS,不应到达 type=$type")
        }
    }
    // 注册 SharedJsScope provider (jsLib 共享 scope 缓存)
    // 未注册时 SharedJsScope.getScope 抛 IllegalStateException, 被 runCatching 吞掉,
    // 表现为书源 jsLib 规则失效 (JS 调用 jsLib 函数报 ReferenceError)
    SharedJsScope.registerProviders { type ->
        when (type) {
            JsEngineType.QUICKJS -> DesktopQuickJsSharedJsScopeProvider
            else -> error("rhino 已弃用,JsEngines.type 固定 QUICKJS,不应到达 type=$type")
        }
    }
    // 注册简繁词典缓存定位器 (替代 app 端 registerAndroidChineseUtils)
    // 桌面端不实现后台拉取, 文件不存在时 ChineseUtils.loadDict fallback 到 quick-transfer 默认词典
    ChineseUtils.pathProvider = DesktopTcDictCachePathProvider
}

/**
 * 桌面端简繁词典缓存文件定位器。
 *
 * 指向 `~/.legado/cache/tc_cache/` 目录 (便携模式跟随 exe 的 `data/cache/tc_cache/`),
 * 与 [io.legado.app.help.file.DesktopAppFilesDir] 的 cacheDir 同根。
 *
 * # 与 app 端 [io.legado.app.utils.TcDictCachePathProvider] (ChineseUtilsUi.kt) 区别
 * - app 端 lambda 内置 `RemoteAssetsUtils.downloadTcIfNeeded(fileName)` 后台拉取副作用,
 *   缺失即异步下载 (依赖 Coroutine.async + RemoteAssetsUtils, 留 app 模块);
 * - 桌面端简化: 仅返回本地路径, 不实现后台拉取 (文件不存在时 ChineseUtils.loadDict
 *   会 fallback 到 quick-transfer 自带默认词典, 行为可用但不持久化)。
 *
 * # 后续扩展
 * 若桌面端需要词典缓存持久化, 可在此处补 HTTP 下载逻辑 (用 OkHttpClientProviders),
 * 当前简化实现满足"功能可用"底线, 词典加载略慢 (每次启动重新加载默认词典)。
 */
private val DesktopTcDictCachePathProvider = TcDictCachePathProvider { fileName ->
    // 与 DesktopAppFilesDir.cacheDir 同根: 便携模式 = data/cache, 开发模式 = ~/.legado/cache
    val cacheRoot = Paths.get(desktopAppRootDir(), "cache", "tc_cache")
    Files.createDirectories(cacheRoot)
    File(cacheRoot.toFile(), fileName)
}
