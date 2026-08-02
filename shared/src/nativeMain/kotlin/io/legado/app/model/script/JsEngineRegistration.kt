package io.legado.app.model.script

import io.legado.app.help.image.ImageOps

/**
 * native JS 引擎注册入口的跨源集门面 (expect 侧)。
 *
 * actual 在 RegisterNativeJsEngines.native.kt (随 nativeInterop stage 进 leaf, 引用
 * [NativeJsEngine] 等 leaf 类), expect 留在 nativeMain 供 iosMain/ohosMain 的
 * registerIosJsEngines / registerOhosJsEngines 直接调用, 签名不含 cinterop 类型。
 */
internal expect fun registerNativeJsEngines(imageOps: ImageOps)
