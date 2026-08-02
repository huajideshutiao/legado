package io.legado.app.model.script

import io.legado.app.data.entities.BaseSource
import io.legado.app.help.JsExtFactory
import io.legado.app.help.JsExtProviders
import io.legado.app.help.image.ImageOps
import io.legado.app.model.SharedJsScope

/**
 * Native (iOS/ohos) 端 JS 引擎注册共享入口。
 *
 * 抽自 iosMain RegisterIosJsEngines / ohosMain RegisterOhosJsEngines,
 * 两端仅 imageOps 参数不同 (IosImageOps / OhosImageOps), 其余注册逻辑完全一致。
 *
 * 调用方: registerIosJsEngines(IosImageOps) / registerOhosJsEngines(OhosImageOps)
 *
 * 本函数为 JsEngineRegistration.kt 中 expect 的 leaf actual (引用 [NativeJsEngine] 等
 * leaf 类, 随文件 stage 进 leaf); expect 声明在 nativeMain, ios/ohos 入口直接调用 expect。
 */
actual fun registerNativeJsEngines(imageOps: ImageOps) {
    // 1. 注册 image 实现到 JsBindingInjector (JsBindings 构造时访问, 必须先注册)
    JsBindingInjector.registerImageOps(imageOps)

    // 2. 注册 NativeJsEngine 到 JsEngines 作为 QUICKJS 引擎实现
    // (JS 引擎注册逻辑已下沉到 nativeMain registerNativeJsEngineProvider, iOS/鸿蒙共用)
    registerNativeJsEngineProvider()

    // 3. 注册 SharedJsScope provider (jsLib 共享 scope 缓存)
    // 未注册时 SharedJsScope.getScope/remove 抛 IllegalStateException, 书源 jsLib 求值全挂
    SharedJsScope.registerProviders { type ->
        when (type) {
            JsEngineType.QUICKJS -> NativeQuickJsSharedJsScopeProvider
            else -> error("rhino 已弃用,JsEngines.type 固定 QUICKJS,不应到达 type=$type")
        }
    }

    // 4. 注册 JsExtFactory: wrap 直返 source (与 desktop DesktopJsExtFactory 一致)。
    // BaseSource 已实现 JsExtensionsCommon, NativeJsExtensionsBridge 注入 bindings 时直接桥接;
    // 未注册时 BaseSource.evalJS 第一行 JsExtProviders.get() 即抛, 书源 JS 全废
    JsExtProviders.register(object : JsExtFactory {
        override fun wrap(source: BaseSource): Any = source
    })
}
