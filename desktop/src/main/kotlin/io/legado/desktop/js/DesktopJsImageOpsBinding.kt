package io.legado.desktop.js

import io.legado.app.model.script.JsBindingInjector
import io.legado.desktop.image.DesktopImageOps

/**
 * JS 图片 API 绑定 (skia DesktopImageOps → JsBindingInjector)。
 *
 * 原 registerDesktopJsEngines 的首行注册 (DesktopImageOps 依赖 skiko/skia, 该文件随
 * "无 UI 核心"抽取下沉 :desktop-core 后, 本 UI 绑定行拆出留在 :desktop)。
 *
 * 调用时机: desktop Main 阶段1 同步注册 (任何 JS eval 之前) —— JsBindings 构造时访问
 * `JsBindingInjector.image` getter, 未注册会 checkNotNull 失败, 任何 JsEngine.eval 都跑不了。
 * headless 无 skia, 由 headless 入口自行注册显式报错的 ImageOps 兜底。
 */
fun registerDesktopJsImageOps() {
    JsBindingInjector.registerImageOps(DesktopImageOps)
}
