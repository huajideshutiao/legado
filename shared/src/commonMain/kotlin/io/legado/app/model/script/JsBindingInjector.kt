package io.legado.app.model.script

import io.legado.app.constant.AppConst
import io.legado.app.help.image.ImageOps
import io.legado.app.model.script.JsBindingInjector.image
import io.legado.app.model.script.JsBindingInjector.registerImageOps
import kotlin.concurrent.Volatile

/**
 * JsBindings 的平台注入点：`platform` 名 + `image` 实现。
 *
 * 集中 [JsBindings] 与各引擎 SharedJsScope provider 的两处平台渗漏。
 * [image] 走 provider 注册而非 expect/actual：平台差异只在「谁供 [ImageOps] 实现」
 * （android=BitmapImageOps 的 Bitmap），是依赖注入而非编译期分叉——同 appString/AppLogHost 先例。
 * 宿主启动早期 [registerImageOps] 注册一次（任何 JS eval 之前，安卓在 App.onCreate）。
 *
 * 注：只暴露值，注入赋值仍由各调用点自行完成——rhino 的 `ScriptBindings.set`
 * 有 `Context.javaToJS` 包装语义，不能统一走 Map.put，故此处不提供 injectInto()。
 */
object JsBindingInjector {

    /** 平台名 String（android/desktop/ios/ohos）。 */
    val platform: Any = AppConst.JS_PLATFORM

    @Volatile
    private var imageOps: ImageOps? = null

    /** 宿主启动早期注册一次（任何 JS eval 之前）。 */
    fun registerImageOps(ops: ImageOps) {
        imageOps = ops
    }

    /** `image` 别名的图片解密 API 实现单例。 */
    val image: Any
        get() = checkNotNull(imageOps) { "ImageOps 未注册(宿主启动早期 registerImageOps)" }

    /** 同 [image] 但带类型且允许未注册, 供 Kotlin 侧调用 (如 [ImageOps.withScope] 作用域回收)。 */
    val imageOpsOrNull: ImageOps?
        get() = imageOps
}
